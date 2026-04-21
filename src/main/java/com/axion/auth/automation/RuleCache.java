package com.axion.auth.automation;

import com.axion.auth.domain.entity.AutomationRule;
import com.axion.auth.domain.entity.RuleKeyword;
import com.axion.auth.repository.AutomationRuleRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory rule cache providing sub-millisecond rule lookups per tenant × account.
 *
 * <h3>Data structure design</h3>
 * <pre>
 *   cacheKey = tenantId + ":" + igAccountId
 *
 *   RuleSet {
 *     welcomeRules  : List&lt;AutomationRule&gt;          // priority-ordered, O(1) first-match
 *     fallbackRules : List&lt;AutomationRule&gt;          // priority-ordered
 *     keywordIndex  : Map&lt;String, List&lt;AutomationRule&gt;&gt;  // token → matching rules
 *   }
 * </pre>
 *
 * <h3>Keyword index</h3>
 * <p>Instead of scanning all rules on every request, we invert the keyword ↔ rule
 * relationship into a {@code Map<String, List<Rule>>}. At match time we tokenize the
 * message text into whitespace-delimited tokens once, then probe the map for each token.
 * Lookup is O(tokens × 1) ≈ O(k) where k is typically 5–20 for a real message.
 *
 * <h3>Thread safety</h3>
 * <p>The outer map is a {@link ConcurrentHashMap}. Individual {@link RuleSet} objects
 * are replaced atomically via {@code put} — callers always see a fully-built snapshot.
 * No locking is needed during reads.
 *
 * <h3>Cache invalidation</h3>
 * <p>Call {@link #invalidate(UUID, String)} + {@link #load(UUID, String)} when rules
 * change. A full reload is triggered at startup via {@link #warmUp()}.
 *
 * <p>Future enhancement: subscribe to a Redis pub/sub channel so all replicas receive
 * invalidation events without DB polling.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuleCache {

    private final AutomationRuleRepository ruleRepository;

    /** Outer map: cacheKey → RuleSet snapshot. */
    private final ConcurrentHashMap<String, RuleSet> cache = new ConcurrentHashMap<>();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Warm-up disabled by default at startup because we don't know tenant IDs
     * at bean-init time. Individual accounts are loaded on first request (lazy load)
     * or via an explicit admin trigger. Override in integration tests.
     */
    @PostConstruct
    void warmUp() {
        log.info("RuleCache initialised (lazy per-account load strategy)");
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the {@link RuleSet} for the given account, loading from DB if absent.
     */
    public RuleSet getOrLoad(UUID tenantId, String igAccountId) {
        String key = cacheKey(tenantId, igAccountId);
        return cache.computeIfAbsent(key, k -> {
            log.info("Rule cache miss — loading rules for account {}", igAccountId);
            return load(tenantId, igAccountId);
        });
    }

    /**
     * Forces a cache reload from DB for a specific account.
     * Call this after rule create/update/delete operations.
     */
    public RuleSet load(UUID tenantId, String igAccountId) {
        List<AutomationRule> rules =
                ruleRepository.findActiveRulesWithKeywords(tenantId, igAccountId);
        RuleSet ruleSet = buildRuleSet(rules);
        cache.put(cacheKey(tenantId, igAccountId), ruleSet);
        log.info("Loaded {} rules into cache for account {} [welcome={}, keyword={} keywords, fallback={}]",
                rules.size(), igAccountId,
                ruleSet.welcomeRules().size(),
                ruleSet.keywordIndex().size(),
                ruleSet.fallbackRules().size());
        return ruleSet;
    }

    /** Removes the cached rules for an account. Next request triggers a reload. */
    public void invalidate(UUID tenantId, String igAccountId) {
        cache.remove(cacheKey(tenantId, igAccountId));
        log.debug("Rule cache invalidated for account {}", igAccountId);
    }

    /** Returns current size of the cache (number of loaded accounts). */
    public int size() {
        return cache.size();
    }

    // ── Build ─────────────────────────────────────────────────────────────────

    /**
     * Constructs a {@link RuleSet} from a list of fully-loaded rules.
     * Rules are assumed to be ordered by priority ASC (as returned by the repository).
     */
    RuleSet buildRuleSet(List<AutomationRule> rules) {
        List<AutomationRule> welcomeRules  = new ArrayList<>();
        List<AutomationRule> fallbackRules = new ArrayList<>();
        // keyword → rules that contain it (insertion order = priority order)
        Map<String, List<AutomationRule>> keywordIndex = new HashMap<>();

        for (AutomationRule rule : rules) {
            switch (rule.getTriggerType()) {
                case WELCOME  -> welcomeRules.add(rule);
                case FALLBACK -> fallbackRules.add(rule);
                case KEYWORD  -> indexKeywords(rule, keywordIndex);
            }
        }

        return new RuleSet(
                Collections.unmodifiableList(welcomeRules),
                Collections.unmodifiableList(fallbackRules),
                Collections.unmodifiableMap(keywordIndex)
        );
    }

    private void indexKeywords(AutomationRule rule, Map<String, List<AutomationRule>> index) {
        for (RuleKeyword rk : rule.getKeywords()) {
            String kw = rk.getKeyword(); // already normalised at persist time
            if (kw != null && !kw.isBlank()) {
                index.computeIfAbsent(kw, k -> new ArrayList<>()).add(rule);
            }
        }
    }

    private static String cacheKey(UUID tenantId, String igAccountId) {
        return tenantId.toString() + ":" + igAccountId;
    }

    // ── RuleSet ───────────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of a single account's rules, structured for fast lookup.
     *
     * @param welcomeRules  WELCOME-type rules, priority-ordered
     * @param fallbackRules FALLBACK-type rules, priority-ordered
     * @param keywordIndex  inverted index: normalised keyword → matching rules (priority-ordered)
     */
    public record RuleSet(
            List<AutomationRule>              welcomeRules,
            List<AutomationRule>              fallbackRules,
            Map<String, List<AutomationRule>> keywordIndex
    ) {
        /**
         * Finds all keyword rules whose keywords appear in the tokenized message text.
         * Returns results de-duplicated and sorted by priority ascending.
         *
         * @param tokens whitespace-tokenized, lowercase message text
         */
        public List<AutomationRule> matchKeywordRules(Set<String> tokens) {
            if (tokens.isEmpty() || keywordIndex.isEmpty()) {
                return List.of();
            }
            // Use a LinkedHashMap keyed by rule ID to deduplicate while preserving
            // the priority order of first encounter.
            Map<java.util.UUID, AutomationRule> matched = new LinkedHashMap<>();
            for (String token : tokens) {
                List<AutomationRule> candidates = keywordIndex.get(token);
                if (candidates != null) {
                    for (AutomationRule r : candidates) {
                        matched.putIfAbsent(r.getId(), r);
                    }
                }
            }
            // Sort by priority once, after dedup (small list — typically <10 rules)
            List<AutomationRule> result = new ArrayList<>(matched.values());
            result.sort(Comparator.comparingInt(AutomationRule::getPriority));
            return result;
        }
    }
}
