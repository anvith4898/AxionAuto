package com.axion.auth.automation;

import com.axion.auth.domain.entity.AutomationRule;
import com.axion.auth.domain.entity.Contact;
import com.axion.auth.domain.entity.RuleKeyword;
import com.axion.auth.domain.model.MessageDTO;
import com.axion.auth.domain.model.MessageType;
import com.axion.auth.repository.AutomationExecutionLogRepository;
import com.axion.auth.repository.ContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AutomationEngine}.
 * All external dependencies mocked via Mockito.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AutomationEngine")
class AutomationEngineTest {

    @Mock RuleCache                      ruleCache;
    @Mock ContactRepository              contactRepository;
    @Mock AutomationExecutionLogRepository executionLogRepository;
    @Mock RuleReplyDispatcher            replyDispatcher;

    AutomationEngine engine;

    static final UUID TENANT    = UUID.randomUUID();
    static final String IG_ACC  = "1234567890";
    static final String SENDER  = "9876543210";

    @BeforeEach
    void setUp() {
        engine = new AutomationEngine(ruleCache, contactRepository, executionLogRepository, replyDispatcher);
    }

    // ── Helper: tokenize ─────────────────────────────────────────────────────

    @Test
    @DisplayName("tokenize(null/blank) returns empty set")
    void tokenizeBlank() {
        assertThat(AutomationEngine.tokenize(null)).isEmpty();
        assertThat(AutomationEngine.tokenize("   ")).isEmpty();
    }

    @Test
    @DisplayName("tokenize splits on whitespace")
    void tokenizeSplits() {
        assertThat(AutomationEngine.tokenize("hello world foo")).containsExactlyInAnyOrder("hello", "world", "foo");
    }

    // ── Helper: resolveTemplate ───────────────────────────────────────────────

    @Test
    @DisplayName("resolveTemplate substitutes {sender_id}")
    void resolveTemplate() {
        MessageDTO msg = message("hello");
        String result = AutomationEngine.resolveTemplate("Hi {sender_id}!", msg);
        assertThat(result).isEqualTo("Hi " + SENDER + "!");
    }

    // ── evaluate: Welcome ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Welcome rule")
    class WelcomeRule {

        @Test
        @DisplayName("fires Welcome rule on first interaction")
        void welcomeFiresOnFirstInteraction() {
            AutomationRule welcome = ruleOf(AutomationRule.TriggerType.WELCOME, 1, List.of());
            RuleCache.RuleSet rs   = ruleSetOf(List.of(welcome), List.of(), Map.of());

            stubContact(true);
            when(ruleCache.getOrLoad(TENANT, IG_ACC)).thenReturn(rs);
            when(executionLogRepository.existsWithinCooldown(any(), any(), any(), any(), any()))
                    .thenReturn(false);

            List<RuleExecutionResult> results = engine.evaluate(TENANT, message("hi"));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).wasSent()).isTrue();
            verify(replyDispatcher).dispatch(eq(TENANT), any(), eq(welcome), anyString());
        }

        @Test
        @DisplayName("does NOT fire Welcome rule on repeat interaction")
        void welcomeSkippedOnRepeat() {
            AutomationRule welcome = ruleOf(AutomationRule.TriggerType.WELCOME, 1, List.of());
            RuleCache.RuleSet rs   = ruleSetOf(List.of(welcome), List.of(), Map.of());

            stubContact(false); // NOT first interaction
            when(ruleCache.getOrLoad(TENANT, IG_ACC)).thenReturn(rs);

            List<RuleExecutionResult> results = engine.evaluate(TENANT, message("hi"));

            // No WELCOME fired; no fallback in the RuleSet → empty
            assertThat(results).isEmpty();
            verifyNoInteractions(replyDispatcher);
        }
    }

    // ── evaluate: Keyword ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Keyword rule")
    class KeywordRule {

        @Test
        @DisplayName("fires keyword rule when message contains exact keyword token")
        void keywordMatch() {
            AutomationRule kw = ruleOf(AutomationRule.TriggerType.KEYWORD, 10, List.of("price"));
            Map<String, List<AutomationRule>> index = Map.of("price", List.of(kw));
            RuleCache.RuleSet rs = ruleSetOf(List.of(), List.of(), index);

            stubContact(false);
            when(ruleCache.getOrLoad(TENANT, IG_ACC)).thenReturn(rs);
            when(executionLogRepository.existsWithinCooldown(any(), any(), any(), any(), any()))
                    .thenReturn(false);

            List<RuleExecutionResult> results = engine.evaluate(TENANT, message("what is your price"));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).wasSent()).isTrue();
        }

        @Test
        @DisplayName("does NOT fire when message has no matching keywords")
        void noKeywordMatch() {
            AutomationRule kw = ruleOf(AutomationRule.TriggerType.KEYWORD, 10, List.of("price"));
            Map<String, List<AutomationRule>> index = Map.of("price", List.of(kw));
            RuleCache.RuleSet rs = ruleSetOf(List.of(), List.of(), index);

            stubContact(false);
            when(ruleCache.getOrLoad(TENANT, IG_ACC)).thenReturn(rs);

            // Message doesn't contain "price"
            List<RuleExecutionResult> results = engine.evaluate(TENANT, message("hello world"));

            assertThat(results).isEmpty();
        }
    }

    // ── evaluate: Fallback ────────────────────────────────────────────────────

    @Nested
    @DisplayName("Fallback rule")
    class FallbackRule {

        @Test
        @DisplayName("fires fallback when no other rule matched")
        void fallbackFires() {
            AutomationRule fb = ruleOf(AutomationRule.TriggerType.FALLBACK, 99, List.of());
            RuleCache.RuleSet rs = ruleSetOf(List.of(), List.of(fb), Map.of());

            stubContact(false);
            when(ruleCache.getOrLoad(TENANT, IG_ACC)).thenReturn(rs);
            when(executionLogRepository.existsWithinCooldown(any(), any(), any(), any(), any()))
                    .thenReturn(false);

            List<RuleExecutionResult> results = engine.evaluate(TENANT, message("something random"));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).wasSent()).isTrue();
        }
    }

    // ── evaluate: Cooldown suppression ───────────────────────────────────────

    @Nested
    @DisplayName("Cooldown / idempotency")
    class Cooldown {

        @Test
        @DisplayName("skips rule execution when within cooldown window")
        void skippedInCooldown() {
            AutomationRule kw = ruleOf(AutomationRule.TriggerType.KEYWORD, 5, List.of("hello"));
            Map<String, List<AutomationRule>> index = Map.of("hello", List.of(kw));
            RuleCache.RuleSet rs = ruleSetOf(List.of(), List.of(), index);

            stubContact(false);
            when(ruleCache.getOrLoad(TENANT, IG_ACC)).thenReturn(rs);
            // Simulate: this rule already fired within cooldown
            when(executionLogRepository.existsWithinCooldown(any(), any(), any(), any(), any()))
                    .thenReturn(true);

            List<RuleExecutionResult> results = engine.evaluate(TENANT, message("hello"));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).wasSkipped()).isTrue();
            verifyNoInteractions(replyDispatcher);
        }
    }

    // ── evaluate: FIRST_MATCH mode ────────────────────────────────────────────

    @Nested
    @DisplayName("ExecutionMode.FIRST_MATCH")
    class FirstMatchMode {

        @Test
        @DisplayName("stops after first SENT rule in FIRST_MATCH mode")
        void stopsAfterFirstSent() {
            AutomationRule r1 = ruleOf(AutomationRule.TriggerType.KEYWORD, 1, List.of("hi"));
            AutomationRule r2 = ruleOf(AutomationRule.TriggerType.KEYWORD, 2, List.of("hi"));
            r2.setExecutionMode(AutomationRule.ExecutionMode.FIRST_MATCH);

            Map<String, List<AutomationRule>> index = Map.of("hi", List.of(r1, r2));
            RuleCache.RuleSet rs = ruleSetOf(List.of(), List.of(), index);

            stubContact(false);
            when(ruleCache.getOrLoad(TENANT, IG_ACC)).thenReturn(rs);
            when(executionLogRepository.existsWithinCooldown(any(), any(), any(), any(), any()))
                    .thenReturn(false);

            List<RuleExecutionResult> results = engine.evaluate(TENANT, message("hi"));

            // Only r1 should have fired
            assertThat(results).hasSize(1);
            assertThat(results.get(0).ruleId()).isEqualTo(r1.getId());
        }
    }

    // ── Builders / stubs ──────────────────────────────────────────────────────

    void stubContact(boolean firstInteraction) {
        doNothing().when(contactRepository).upsertContact(any(), any(), any(), any());
        Contact c = Contact.builder()
                .tenantId(TENANT).igAccountId(IG_ACC).senderId(SENDER)
                .interactionCount(firstInteraction ? 1L : 5L)
                .build();
        when(contactRepository.findByTenantIdAndIgAccountIdAndSenderId(TENANT, IG_ACC, SENDER))
                .thenReturn(Optional.of(c));

        // executionLogRepository.save is void-returning — always OK
        lenient().when(executionLogRepository.save(any())).thenAnswer(i -> i.getArguments()[0]);
    }

    AutomationRule ruleOf(AutomationRule.TriggerType type, int priority, List<String> keywords) {
        AutomationRule r = new AutomationRule();
        r.setId(UUID.randomUUID());
        r.setTriggerType(type);
        r.setPriority(priority);
        r.setActive(true);
        r.setReplyText("Auto reply from " + type);
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

    RuleCache.RuleSet ruleSetOf(
            List<AutomationRule> welcome,
            List<AutomationRule> fallback,
            Map<String, List<AutomationRule>> index) {
        return new RuleCache.RuleSet(welcome, fallback, index);
    }

    MessageDTO message(String text) {
        return new MessageDTO(SENDER, IG_ACC, "mid-001", text,
                MessageType.DM, Instant.now(), "mid-001", IG_ACC);
    }
}
