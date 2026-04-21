package com.axion.auth.automation;

import com.axion.auth.domain.entity.AutomationExecutionLog;
import com.axion.auth.domain.entity.AutomationExecutionLog.ExecutionStatus;
import com.axion.auth.domain.entity.AutomationRule;
import com.axion.auth.domain.entity.AutomationRule.ExecutionMode;
import com.axion.auth.domain.entity.Contact;
import com.axion.auth.domain.model.MessageDTO;
import com.axion.auth.repository.AutomationExecutionLogRepository;
import com.axion.auth.repository.ContactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Core automation engine.
 *
 * <h3>Execution flow per inbound {@link MessageDTO}</h3>
 * <pre>
 *  1. Upsert contact → determine if first interaction
 *  2. Load RuleSet from in-memory cache (lazy DB load on first request)
 *  3. Candidate selection:
 *       a. If first interaction → include WELCOME rules
 *       b. Tokenize message text → probe keyword index
 *       c. If (a) or (b) produced no matches → include FALLBACK rules
 *  4. Merge candidates, sort by priority asc, deduplicate by rule ID
 *  5. For each candidate (respecting ExecutionMode):
 *       i.  Cooldown check (DB)
 *       ii. If OK → dispatch reply via InstagramMessageSenderService
 *      iii. Write execution log (SENT or SKIPPED)
 *      iv.  If FIRST_MATCH → break
 * </pre>
 *
 * <h3>Performance</h3>
 * <ul>
 *   <li>Rule resolution: O(tokens) against an in-memory inverted index — no DB scan</li>
 *   <li>Cooldown: single indexed DB read</li>
 *   <li>Contact upsert: single PG ON CONFLICT DO UPDATE</li>
 *   <li>P99 target &lt; 100 ms for the rule evaluation path</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationEngine {

    private final RuleCache                      ruleCache;
    private final ContactRepository              contactRepository;
    private final AutomationExecutionLogRepository executionLogRepository;
    private final RuleReplyDispatcher            replyDispatcher;

    /**
     * Evaluates all applicable automation rules for a given inbound message.
     *
     * @param tenantId  tenant owning this account
     * @param message   fully normalised {@link MessageDTO} from chunk 4
     * @return list of {@link RuleExecutionResult}s — one per rule that was processed
     */
    @Transactional
    public List<RuleExecutionResult> evaluate(UUID tenantId, MessageDTO message) {
        long startMs = System.currentTimeMillis();

        // ── 1. Upsert contact ───────────────────────────────────────────────
        contactRepository.upsertContact(
                tenantId, message.igAccountId(), message.senderId(), Instant.now());

        Optional<Contact> contact = contactRepository
                .findByTenantIdAndIgAccountIdAndSenderId(
                        tenantId, message.igAccountId(), message.senderId());

        boolean isFirstInteraction = contact.map(Contact::isFirstInteraction).orElse(true);
        log.debug("[engine] contact={}, firstInteraction={}", message.senderId(), isFirstInteraction);

        // ── 2. Load rules from cache ────────────────────────────────────────
        RuleCache.RuleSet ruleSet = ruleCache.getOrLoad(tenantId, message.igAccountId());

        // ── 3. Candidate selection ──────────────────────────────────────────
        List<AutomationRule> candidates = selectCandidates(message, ruleSet, isFirstInteraction);

        if (candidates.isEmpty()) {
            log.debug("[engine] No matching rules for message {} from sender {}",
                    message.messageId(), message.senderId());
            return List.of();
        }

        // ── 4. Determine execution mode (use the mode from the first candidate) ──
        ExecutionMode mode = candidates.get(0).getExecutionMode();
        log.debug("[engine] Evaluating {} candidate rules in {} mode", candidates.size(), mode);

        // ── 5. Execute candidates ──────────────────────────────────────────
        List<RuleExecutionResult> results = executeRules(tenantId, message, candidates, mode);

        log.info("[engine] Evaluated {} rules in {}ms [sender={}, mid={}]",
                results.size(), System.currentTimeMillis() - startMs,
                message.senderId(), message.messageId());

        return results;
    }

    // ── Candidate selection ───────────────────────────────────────────────────

    private List<AutomationRule> selectCandidates(
            MessageDTO message,
            RuleCache.RuleSet ruleSet,
            boolean isFirstInteraction) {

        LinkedHashMap<UUID, AutomationRule> seen = new LinkedHashMap<>();

        // (a) Welcome rules for first-interaction contacts
        if (isFirstInteraction) {
            for (AutomationRule r : ruleSet.welcomeRules()) {
                seen.put(r.getId(), r);
            }
        }

        // (b) Keyword rules — O(tokens) inverted-index lookup
        if (message.hasText()) {
            Set<String> tokens = tokenize(message.messageText());
            for (AutomationRule r : ruleSet.matchKeywordRules(tokens)) {
                seen.put(r.getId(), r);   // putIfAbsent ordering: welcome rules stay first
            }
        }

        // (c) Fallback if still nothing matched
        if (seen.isEmpty()) {
            for (AutomationRule r : ruleSet.fallbackRules()) {
                seen.put(r.getId(), r);
            }
        }

        // Sort merged set by priority (stable for same-priority rules)
        List<AutomationRule> result = new ArrayList<>(seen.values());
        result.sort(Comparator.comparingInt(AutomationRule::getPriority));
        return result;
    }

    // ── Rule execution ────────────────────────────────────────────────────────

    private List<RuleExecutionResult> executeRules(
            UUID tenantId,
            MessageDTO message,
            List<AutomationRule> candidates,
            ExecutionMode mode) {

        List<RuleExecutionResult> results = new ArrayList<>(candidates.size());

        for (AutomationRule rule : candidates) {
            RuleExecutionResult result = executeOne(tenantId, message, rule);
            results.add(result);

            if (mode == ExecutionMode.FIRST_MATCH && result.status() == ExecutionStatus.SENT) {
                log.debug("[engine] FIRST_MATCH: stopping after rule '{}' fired", rule.getName());
                break;
            }
        }

        return results;
    }

    private RuleExecutionResult executeOne(UUID tenantId, MessageDTO message, AutomationRule rule) {
        // ── Cooldown / idempotency check ─────────────────────────────────────
        if (rule.getCooldownSeconds() > 0) {
            Instant since = Instant.now().minusSeconds(rule.getCooldownSeconds());
            boolean alreadyFired = executionLogRepository.existsWithinCooldown(
                    tenantId, message.igAccountId(), message.senderId(), rule.getId(), since);

            if (alreadyFired) {
                log.debug("[engine] Rule '{}' suppressed by cooldown for sender {}",
                        rule.getName(), message.senderId());
                return logAndReturn(tenantId, message, rule, ExecutionStatus.SKIPPED,
                        "Cooldown active");
            }
        }

        // ── Dispatch reply ────────────────────────────────────────────────────
        try {
            String resolvedReply = resolveTemplate(rule.getReplyText(), message);
            replyDispatcher.dispatch(tenantId, message, rule, resolvedReply);

            return logAndReturn(tenantId, message, rule, ExecutionStatus.SENT, null);

        } catch (Exception e) {
            log.error("[engine] Failed to dispatch reply for rule '{}' to sender {}",
                    rule.getName(), message.senderId(), e);
            return logAndReturn(tenantId, message, rule, ExecutionStatus.FAILED, e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Tokenizes normalised message text into a set of whitespace-delimited tokens.
     * Input is already lowercase (from Chunk 4 normalizer) so no extra lowercasing needed.
     */
    static Set<String> tokenize(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return Set.of();
        }
        return Set.of(normalizedText.split("\\s+"));
    }

    /** Resolves simple {placeholder} substitutions in a reply template. */
    static String resolveTemplate(String template, MessageDTO message) {
        if (template == null) return "";
        return template
                .replace("{sender_id}",    message.senderId())
                .replace("{message_id}",   message.messageId())
                .replace("{ig_account_id}", message.igAccountId());
    }

    private RuleExecutionResult logAndReturn(
            UUID tenantId, MessageDTO message, AutomationRule rule,
            ExecutionStatus status, String note) {

        AutomationExecutionLog logEntry = AutomationExecutionLog.builder()
                .tenantId(tenantId)
                .igAccountId(message.igAccountId())
                .senderId(message.senderId())
                .ruleId(rule.getId())
                .messageId(message.messageId())
                .status(status)
                .build();

        executionLogRepository.save(logEntry);

        return new RuleExecutionResult(rule.getId(), rule.getName(), status, note);
    }
}
