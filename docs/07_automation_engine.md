# Automation Engine

## Overview

The automation engine is the decision-making core of AxionAuto. Given a normalized inbound `MessageDTO`, it evaluates a set of configured rules, determines which rules should fire, enforces per-rule cooldowns, and delegates to `RuleReplyDispatcher` to send the reply.

All rule data is served from an **in-memory inverted-index cache** (`RuleCache`). The DB is the source of truth but is queried only on cache miss (typically once per account per deployment).

---

## AutomationRule Entity

```java
AutomationRule {
    UUID          id
    UUID          tenantId
    String        igAccountId
    String        name
    TriggerType   triggerType   // WELCOME | KEYWORD | FALLBACK
    ExecutionMode executionMode // FIRST_MATCH | RUN_ALL
    String        replyText     // supports {sender_id}, {message_id}, {ig_account_id} placeholders
    int           priority      // lower = higher priority (e.g. 0 = first)
    boolean       active
    long          cooldownSeconds  // 0 = no cooldown
    List<RuleKeyword> keywords     // only for KEYWORD rules; eagerly loaded
}
```

### Trigger Types

| Type | When fires | Notes |
|---|---|---|
| `WELCOME` | On the **first interaction** from a sender | Detected via `contact.isFirstInteraction()` |
| `KEYWORD` | When the message text contains a matched keyword | Case-insensitive; inverted-index lookup |
| `FALLBACK` | When **no** other rule matched | Catch-all; last resort |

### Execution Modes

| Mode | Behavior |
|---|---|
| `FIRST_MATCH` | Stops after the first `SENT` execution (highest-priority rule wins) |
| `RUN_ALL` | Fires every matching rule, ordered by priority ascending (up to `max-rules-per-message`) |

---

## Rule Evaluation Flow

```
evaluate(tenantId, message):
│
├─[1] UPSERT CONTACT
│     contactRepository.upsertContact(tenantId, igAccountId, senderId, now)
│     → determine isFirstInteraction (bool from Contact entity)
│
├─[2] LOAD RULE SET (from RuleCache — sub-millisecond)
│     ruleCache.getOrLoad(tenantId, igAccountId)
│
├─[3] CANDIDATE SELECTION
│     a. isFirstInteraction? → add WELCOME rules to candidates
│     b. message.hasText()?  → tokenize text → probe keywordIndex
│     c. candidates empty?   → add FALLBACK rules
│     → merge, sort by priority ASC, deduplicate by ruleId
│
├─[4] DETERMINE EXECUTION MODE from first candidate's executionMode
│
└─[5] EXECUTE EACH CANDIDATE
      for each rule:
      ├─[5a] COOLDOWN CHECK (DB)
      │      If rule fired for this (tenantId, igAccountId, senderId, ruleId) within cooldownSeconds → SKIP
      ├─[5b] RESOLVE TEMPLATE
      │      replyText.replace("{sender_id}", ...).replace(...)
      ├─[5c] DISPATCH REPLY
      │      replyDispatcher.dispatch(tenantId, message, rule, resolvedReply)
      ├─[5d] LOG EXECUTION
      │      Save AutomationExecutionLog (SENT | SKIPPED | FAILED)
      └─[5e] FIRST_MATCH break? only if status == SENT
```

---

## Candidate Selection Detail

### Step a — WELCOME Rules
If `contact.isFirstInteraction()` is `true` (the contact has never sent a message before to this account), all WELCOME rules are added to the candidate set.

### Step b — Keyword Matching
```java
// Tokenize message text (already lowercased by the normalizer):
Set<String> tokens = Set.of(text.split("\\s+"));
// e.g. "what are your prices?" → {"what", "are", "your", "prices?"}

// Probe the inverted index:
for (String token : tokens) {
    List<AutomationRule> candidates = keywordIndex.get(token);
    // keywordIndex = { "price": [rule1, rule3], "pricing": [rule1], ... }
}
```

Keywords are stored normalized (trimmed, lowercased) at rule creation time. The tokenizer splits message text on whitespace. Partial matches (e.g., "prices?" matching keyword "prices") are not supported — full token equality is required.

### Step c — FALLBACK Rules
Added only if the candidate set is still empty after steps (a) and (b).

---

## Cooldown Check

```java
// Check: did this rule fire for this sender within the cooldown window?
Instant since = Instant.now().minusSeconds(rule.getCooldownSeconds());

boolean alreadyFired = executionLogRepository.existsWithinCooldown(
    tenantId, igAccountId, senderId, rule.getId(), since);
```

JPQL query:
```sql
SELECT COUNT(l) > 0 FROM AutomationExecutionLog l
WHERE l.tenantId    = :tenantId
  AND l.igAccountId = :igAccountId
  AND l.senderId    = :senderId
  AND l.ruleId      = :ruleId
  AND l.status      = SENT
  AND l.executedAt  >= :since
```

This uses the composite index on `(tenant_id, ig_account_id, sender_id, rule_id, status, executed_at)` for O(1) lookup.

---

## Reply Template Resolution

```java
static String resolveTemplate(String template, MessageDTO message) {
    return template
        .replace("{sender_id}",     message.senderId())
        .replace("{message_id}",    message.messageId())
        .replace("{ig_account_id}", message.igAccountId());
}
```

Example: `"Hi {sender_id}! Thanks for asking about pricing."` becomes `"Hi 10158001! Thanks for asking about pricing."`

Future enhancement: support more sophisticated template variables (contact name from CRM, rule-specific config from JSONB).

---

## RuleCache — In-Memory Inverted Index

### Data Structure

```
ConcurrentHashMap<String, RuleSet>
  └─ key: "{tenantId}:{igAccountId}"
  └─ value: RuleSet (immutable snapshot)

RuleSet {
    List<AutomationRule>              welcomeRules   // priority-sorted, O(1) first-match
    List<AutomationRule>              fallbackRules  // priority-sorted
    Map<String, List<AutomationRule>> keywordIndex   // token → matching rules
}
```

### Why an Inverted Index?

Without an index, keyword matching would scan all KEYWORD rules for every message — O(rules × keywords). With the inverted index, lookup is O(tokens × 1) where tokens is the number of words in the message (typically 5–20). For an account with 100 keyword rules and 500 total keywords, this is ~100× faster.

### Thread Safety

- The outer `ConcurrentHashMap` is lock-free for reads.
- `computeIfAbsent` (used by `getOrLoad`) is atomic — if two threads both miss the cache simultaneously, only one will call `load()` from the DB.
- Individual `RuleSet` objects are immutable (`Collections.unmodifiableList`, `Collections.unmodifiableMap`) and replaced atomically via `put`.

### Cache Invalidation

```java
// After any rule create/update/delete operation:
ruleCache.invalidate(tenantId, igAccountId);   // removes from map
ruleCache.load(tenantId, igAccountId);         // force-reloads from DB
```

The next `getOrLoad` call will build a fresh `RuleSet`. Future enhancement: Redis pub/sub invalidation so all horizontal replicas receive the signal.

---

## AutomationExecutionLog Entity

Records every rule evaluation for auditability and cooldown enforcement:

| Column | Type | Description |
|---|---|---|
| `id` | UUID | Primary key |
| `tenant_id` | UUID | Tenant discriminator |
| `ig_account_id` | STRING | IG Business Account |
| `sender_id` | STRING | IGSID of the contact |
| `rule_id` | UUID | FK to `automation_rules` |
| `message_id` | STRING | Meta message ID |
| `status` | ENUM | `SENT`, `SKIPPED`, `FAILED` |
| `executed_at` | TIMESTAMPTZ | Timestamp of the execution |
| `outbound_message_id` | UUID | FK to `messages.id` (added V4) |

### Execution Status Meanings

| Status | Meaning |
|---|---|
| `SENT` | Reply was sent successfully via the Graph API |
| `SKIPPED` | Cooldown was active; rule was suppressed |
| `FAILED` | Dispatch threw an exception (Resilience4j exhausted retries, rate limit hit, etc.) |

---

## RuleExecutionResult — Return Type

```java
public record RuleExecutionResult(
    UUID            ruleId,
    String          ruleName,
    ExecutionStatus status,
    String          note    // null for SENT; "Cooldown active" for SKIPPED; exception message for FAILED
) {}
```

The `AutomationEngine.evaluate()` method returns `List<RuleExecutionResult>` to the caller (`WebhookEventOrchestrator`) for observability and logging.

---

## Contact Upsert

On every message, the engine upserts into the `contacts` table:

```sql
INSERT INTO contacts (tenant_id, ig_account_id, sender_id, first_seen_at, last_seen_at, message_count)
VALUES (:tenantId, :igAccountId, :senderId, NOW(), NOW(), 1)
ON CONFLICT (tenant_id, ig_account_id, sender_id) DO UPDATE
    SET last_seen_at  = NOW(),
        message_count = contacts.message_count + 1;
```

`isFirstInteraction` is determined by whether `message_count == 1` after the upsert (or equivalently, whether `first_seen_at == last_seen_at`).

---

## Performance Characteristics

| Operation | Complexity | Notes |
|---|---|---|
| Rule cache lookup | O(1) `ConcurrentHashMap.get` | Sub-millisecond |
| Keyword matching | O(tokens × 1) | Typically 5–20 ops |
| Cooldown check | O(1) indexed DB read | Compound index on execution log |
| Contact upsert | O(1) ON CONFLICT | Single PG round-trip |
| Reply send | Network-bound | Covered by Resilience4j retry |
| Full evaluation P99 target | < 100ms | Measured via `log.info("[engine] Evaluated N rules in Xms")` |
