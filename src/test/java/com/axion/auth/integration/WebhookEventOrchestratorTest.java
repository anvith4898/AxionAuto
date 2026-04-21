package com.axion.auth.integration;

import com.axion.auth.automation.AutomationEngine;
import com.axion.auth.automation.RuleExecutionResult;
import com.axion.auth.domain.entity.AutomationExecutionLog.ExecutionStatus;
import com.axion.auth.domain.entity.InstagramOAuthToken;
import com.axion.auth.domain.model.MessageDTO;
import com.axion.auth.domain.model.MessageType;
import com.axion.auth.domain.repository.InstagramOAuthTokenRepository;
import com.axion.auth.service.ConversationMessageService;
import com.axion.auth.service.TokenEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WebhookEventOrchestrator}.
 *
 * <p>Verifies token resolution paths and correct delegation to the
 * {@link AutomationEngine} under various conditions.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookEventOrchestrator")
class WebhookEventOrchestratorTest {

    @Mock AutomationEngine              automationEngine;
    @Mock InstagramOAuthTokenRepository tokenRepository;
    @Mock TokenEncryptionService        encryptionService;
    @Mock WebhookEventStatusUpdater     statusUpdater;
    @Mock ConversationMessageService    conversationMessageService;

    @InjectMocks
    WebhookEventOrchestrator orchestrator;

    private static final UUID   TENANT_ID    = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String IG_ACCOUNT   = "17841400123456789";
    private static final String SENDER_ID    = "1234567890";
    private static final String MESSAGE_ID   = "mid.test.abc123";
    private static final String EVENT_ID     = "evt-001";
    private static final String PLAIN_TOKEN  = "EAAG_test_token";

    private MessageDTO validMessage;
    private InstagramOAuthToken activeToken;

    @BeforeEach
    void setUp() {
        validMessage = new MessageDTO(
                SENDER_ID, IG_ACCOUNT, MESSAGE_ID,
                "pricing", MessageType.DM,
                Instant.now(), MESSAGE_ID, IG_ACCOUNT
        );

        activeToken = InstagramOAuthToken.builder()
                .id(UUID.randomUUID())
                .tenantId(TENANT_ID)
                .instagramAccountId(IG_ACCOUNT)
                .status(InstagramOAuthToken.TokenStatus.ACTIVE)
                .tokenExpiry(Instant.now().plusSeconds(86400))
                .accessTokenEncrypted(new byte[]{1, 2, 3})
                .accessTokenIv(new byte[]{4, 5, 6})
                .accessTokenTag(new byte[]{7, 8, 9})
                .build();
    }

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Happy path")
    class HappyPath {

        @Test
        @DisplayName("returns SUCCESS when engine evaluates rules and at least one is SENT")
        void successPath() {
            when(tokenRepository.findByTenantIdAndInstagramAccountId(TENANT_ID, IG_ACCOUNT))
                    .thenReturn(Optional.of(activeToken));
            when(encryptionService.decrypt(any(), any(), any())).thenReturn(PLAIN_TOKEN);

            RuleExecutionResult sentResult = new RuleExecutionResult(
                    UUID.randomUUID(), "Pricing Rule", ExecutionStatus.SENT, null);
            when(automationEngine.evaluate(TENANT_ID, validMessage))
                    .thenReturn(List.of(sentResult));

            WebhookEventOrchestrator.PipelineResult result =
                    orchestrator.process(TENANT_ID, validMessage, EVENT_ID);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.ruleResults()).hasSize(1);
            assertThat(result.ruleResults().get(0).status()).isEqualTo(ExecutionStatus.SENT);

            verify(statusUpdater).markProcessing(EVENT_ID);
            verify(statusUpdater).markProcessed(EVENT_ID);
            verify(automationEngine).evaluate(TENANT_ID, validMessage);
        }

        @Test
        @DisplayName("returns SUCCESS with SKIPPED rule when cooldown active")
        void skippedByEngine() {
            when(tokenRepository.findByTenantIdAndInstagramAccountId(TENANT_ID, IG_ACCOUNT))
                    .thenReturn(Optional.of(activeToken));
            when(encryptionService.decrypt(any(), any(), any())).thenReturn(PLAIN_TOKEN);

            RuleExecutionResult skipped = new RuleExecutionResult(
                    UUID.randomUUID(), "Welcome Rule", ExecutionStatus.SKIPPED, "Cooldown active");
            when(automationEngine.evaluate(TENANT_ID, validMessage))
                    .thenReturn(List.of(skipped));

            WebhookEventOrchestrator.PipelineResult result =
                    orchestrator.process(TENANT_ID, validMessage, EVENT_ID);

            // Only FAILED rules with 0 successful sends trigger FAILURE outcome
            assertThat(result.isSuccess()).isTrue();
            verify(statusUpdater).markProcessed(EVENT_ID);
        }
    }

    // ── Token resolution failures ──────────────────────────────────────────────

    @Nested
    @DisplayName("Token resolution failures")
    class TokenResolutionFailures {

        @Test
        @DisplayName("returns SKIPPED when no token on record for this account")
        void noTokenFound() {
            when(tokenRepository.findByTenantIdAndInstagramAccountId(TENANT_ID, IG_ACCOUNT))
                    .thenReturn(Optional.empty());

            WebhookEventOrchestrator.PipelineResult result =
                    orchestrator.process(TENANT_ID, validMessage, EVENT_ID);

            assertThat(result.isSkipped()).isTrue();
            assertThat(result.note()).contains("No ACTIVE token");
            verify(statusUpdater).markFailed(eq(EVENT_ID), anyString());
            verifyNoInteractions(automationEngine);
        }

        @Test
        @DisplayName("returns SKIPPED when token status is not ACTIVE")
        void tokenNotActive() {
            activeToken.setStatus(InstagramOAuthToken.TokenStatus.EXPIRED);
            when(tokenRepository.findByTenantIdAndInstagramAccountId(TENANT_ID, IG_ACCOUNT))
                    .thenReturn(Optional.of(activeToken));

            WebhookEventOrchestrator.PipelineResult result =
                    orchestrator.process(TENANT_ID, validMessage, EVENT_ID);

            assertThat(result.isSkipped()).isTrue();
            verifyNoInteractions(automationEngine);
        }

        @Test
        @DisplayName("returns SKIPPED when token is expired")
        void tokenExpired() {
            activeToken.setTokenExpiry(Instant.now().minusSeconds(10));
            when(tokenRepository.findByTenantIdAndInstagramAccountId(TENANT_ID, IG_ACCOUNT))
                    .thenReturn(Optional.of(activeToken));

            WebhookEventOrchestrator.PipelineResult result =
                    orchestrator.process(TENANT_ID, validMessage, EVENT_ID);

            assertThat(result.isSkipped()).isTrue();
            verifyNoInteractions(automationEngine);
        }

        @Test
        @DisplayName("returns SKIPPED when token decryption throws")
        void decryptionFailure() {
            when(tokenRepository.findByTenantIdAndInstagramAccountId(TENANT_ID, IG_ACCOUNT))
                    .thenReturn(Optional.of(activeToken));
            when(encryptionService.decrypt(any(), any(), any()))
                    .thenThrow(new RuntimeException("Key rotation mismatch"));

            WebhookEventOrchestrator.PipelineResult result =
                    orchestrator.process(TENANT_ID, validMessage, EVENT_ID);

            assertThat(result.isSkipped()).isTrue();
            verifyNoInteractions(automationEngine);
        }
    }

    // ── Engine failures ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Engine failure handling")
    class EngineFailures {

        @Test
        @DisplayName("returns FAILURE when all rule executions are FAILED")
        void allRulesFailed() {
            when(tokenRepository.findByTenantIdAndInstagramAccountId(TENANT_ID, IG_ACCOUNT))
                    .thenReturn(Optional.of(activeToken));
            when(encryptionService.decrypt(any(), any(), any())).thenReturn(PLAIN_TOKEN);

            RuleExecutionResult failed = new RuleExecutionResult(
                    UUID.randomUUID(), "Pricing Rule", ExecutionStatus.FAILED, "API error");
            when(automationEngine.evaluate(TENANT_ID, validMessage))
                    .thenReturn(List.of(failed));

            WebhookEventOrchestrator.PipelineResult result =
                    orchestrator.process(TENANT_ID, validMessage, EVENT_ID);

            assertThat(result.isFailure()).isTrue();
            verify(statusUpdater).markFailed(eq(EVENT_ID), anyString());
        }

        @Test
        @DisplayName("returns SUCCESS when mix of SENT and FAILED rules — at least one sent")
        void mixedResults() {
            when(tokenRepository.findByTenantIdAndInstagramAccountId(TENANT_ID, IG_ACCOUNT))
                    .thenReturn(Optional.of(activeToken));
            when(encryptionService.decrypt(any(), any(), any())).thenReturn(PLAIN_TOKEN);

            List<RuleExecutionResult> mixed = List.of(
                    new RuleExecutionResult(UUID.randomUUID(), "Rule A", ExecutionStatus.SENT, null),
                    new RuleExecutionResult(UUID.randomUUID(), "Rule B", ExecutionStatus.FAILED, "error")
            );
            when(automationEngine.evaluate(TENANT_ID, validMessage)).thenReturn(mixed);

            WebhookEventOrchestrator.PipelineResult result =
                    orchestrator.process(TENANT_ID, validMessage, EVENT_ID);

            assertThat(result.isSuccess()).isTrue();
            verify(statusUpdater).markProcessed(EVENT_ID);
        }
    }
}
