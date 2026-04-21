package com.axion.auth.automation;

import com.axion.auth.domain.entity.AutomationRule;
import com.axion.auth.domain.entity.RuleKeyword;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link RuleCache} — specifically the {@link RuleCache#buildRuleSet}
 * and {@link RuleCache.RuleSet#matchKeywordRules} logic.
 * No Spring context, no DB.
 */
@DisplayName("RuleCache")
class RuleCacheTest {

    private RuleCache cache;

    @BeforeEach
    void setUp() {
        // We only test buildRuleSet; the DB-loading path is mocked in engine tests
        cache = new RuleCache(mock(com.axion.auth.repository.AutomationRuleRepository.class));
    }

    @Nested
    @DisplayName("buildRuleSet")
    class BuildRuleSet {

        @Test
        @DisplayName("separates WELCOME, KEYWORD, FALLBACK rules into correct buckets")
        void separatesRulesByType() {
            AutomationRule welcome  = rule(AutomationRule.TriggerType.WELCOME,  1, List.of());
            AutomationRule keyword  = rule(AutomationRule.TriggerType.KEYWORD,  2, List.of("hello"));
            AutomationRule fallback = rule(AutomationRule.TriggerType.FALLBACK, 3, List.of());

            RuleCache.RuleSet rs = cache.buildRuleSet(List.of(welcome, keyword, fallback));

            assertThat(rs.welcomeRules()).containsExactly(welcome);
            assertThat(rs.fallbackRules()).containsExactly(fallback);
            assertThat(rs.keywordIndex()).containsKey("hello");
        }

        @Test
        @DisplayName("empty rule list produces empty RuleSet")
        void emptyRules() {
            RuleCache.RuleSet rs = cache.buildRuleSet(List.of());

            assertThat(rs.welcomeRules()).isEmpty();
            assertThat(rs.fallbackRules()).isEmpty();
            assertThat(rs.keywordIndex()).isEmpty();
        }

        @Test
        @DisplayName("multiple keywords on one rule are all indexed")
        void multipleKeywords() {
            AutomationRule r = rule(AutomationRule.TriggerType.KEYWORD, 1,
                                    List.of("price", "cost", "fee"));
            RuleCache.RuleSet rs = cache.buildRuleSet(List.of(r));

            assertThat(rs.keywordIndex()).containsKeys("price", "cost", "fee");
            assertThat(rs.keywordIndex().get("price")).containsExactly(r);
        }

        @Test
        @DisplayName("two rules sharing a keyword both appear in the index")
        void sharedKeyword() {
            AutomationRule r1 = rule(AutomationRule.TriggerType.KEYWORD, 10, List.of("hi"));
            AutomationRule r2 = rule(AutomationRule.TriggerType.KEYWORD, 20, List.of("hi"));

            RuleCache.RuleSet rs = cache.buildRuleSet(List.of(r1, r2));

            List<AutomationRule> candidates = rs.keywordIndex().get("hi");
            assertThat(candidates).containsExactly(r1, r2);  // priority order preserved
        }
    }

    @Nested
    @DisplayName("RuleSet.matchKeywordRules")
    class MatchKeywordRules {

        @Test
        @DisplayName("returns empty list when no keywords match")
        void noMatch() {
            RuleCache.RuleSet rs = cache.buildRuleSet(List.of(
                    rule(AutomationRule.TriggerType.KEYWORD, 1, List.of("price"))
            ));
            assertThat(rs.matchKeywordRules(Set.of("hello", "world"))).isEmpty();
        }

        @Test
        @DisplayName("returns matching rule for single token match")
        void singleMatch() {
            AutomationRule r = rule(AutomationRule.TriggerType.KEYWORD, 1, List.of("price"));
            RuleCache.RuleSet rs = cache.buildRuleSet(List.of(r));

            List<AutomationRule> matches = rs.matchKeywordRules(Set.of("what", "is", "your", "price"));

            assertThat(matches).containsExactly(r);
        }

        @Test
        @DisplayName("deduplicates rule matched by multiple tokens")
        void deduplication() {
            AutomationRule r = rule(AutomationRule.TriggerType.KEYWORD, 1, List.of("price", "cost"));
            RuleCache.RuleSet rs = cache.buildRuleSet(List.of(r));

            // Message contains both "price" and "cost" → rule should appear only once
            List<AutomationRule> matches = rs.matchKeywordRules(Set.of("price", "cost"));

            assertThat(matches).hasSize(1).containsExactly(r);
        }

        @Test
        @DisplayName("returns results sorted by priority ascending")
        void priorityOrder() {
            AutomationRule high = rule(AutomationRule.TriggerType.KEYWORD, 5,  List.of("hello"));
            AutomationRule low  = rule(AutomationRule.TriggerType.KEYWORD, 50, List.of("hello"));
            RuleCache.RuleSet rs = cache.buildRuleSet(List.of(low, high)); // intentionally reversed

            List<AutomationRule> matches = rs.matchKeywordRules(Set.of("hello"));

            assertThat(matches).containsExactly(high, low);
        }

        @Test
        @DisplayName("returns empty list for empty tokens")
        void emptyTokens() {
            RuleCache.RuleSet rs = cache.buildRuleSet(List.of(
                    rule(AutomationRule.TriggerType.KEYWORD, 1, List.of("hello"))
            ));
            assertThat(rs.matchKeywordRules(Set.of())).isEmpty();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    AutomationRule rule(AutomationRule.TriggerType type, int priority, List<String> keywords) {
        AutomationRule r = new AutomationRule();
        r.setId(UUID.randomUUID());
        r.setTriggerType(type);
        r.setPriority(priority);
        r.setActive(true);
        r.setReplyText("Reply from rule " + priority);
        r.setExecutionMode(AutomationRule.ExecutionMode.FIRST_MATCH);
        r.setCooldownSeconds(3600L);
        r.setKeywords(keywords.stream().map(kw -> {
            RuleKeyword rk = new RuleKeyword();
            rk.setRule(r);
            rk.setKeyword(kw);
            return rk;
        }).toList());
        return r;
    }
}
