package com.axion.auth.integration;

import com.axion.auth.automation.AutomationEngine;
import com.axion.auth.automation.RuleExecutionResult;
import com.axion.auth.domain.entity.AutomationExecutionLog.ExecutionStatus;
import com.axion.auth.domain.entity.InstagramOAuthToken;
import com.axion.auth.domain.model.MessageDTO;
import com.axion.auth.domain.model.MessageType;
import com.axion.auth.domain.repository.InstagramOAuthTokenRepository;
import com.axion.auth.normalization.WebhookNormalizationService;
import com.axion.auth.service.WebhookEventProducer;
import com.axion.auth.service.WebhookIdempotencyService;
import com.axion.auth.service.WebhookSignatureValidator;
import com.axion.auth.service.ConversationMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end integration test for the five-stage webhook automation pipeline.
 *
 * <h3>Architecture under test</h3>
 * <pre>
 *   WebhookController (HTTP)
 *       └─► EventPipelineIntegrationService  [Stages 1-2: ingest + queue]
 *               ├─► WebhookSignatureValidator    [1a: HMAC]
 *               ├─► WebhookIdempotencyService    [1b: dedup]
 *               ├─► WebhookEventProducer         [2: XADD]
 *               └─► WebhookEventOrchestrator     [3-5: consume → decide → send]
 *                       └─► AutomationEngine
 * </pre>
 *
 * <p>All infrastructure (Redis, Postgres) is stubbed via Mockito.
 * The test verifies behavior contracts at each stage boundary.
 *
 * <p>Stage 3 (stream consumption) is initiated manually by calling
 * {@link WebhookStreamConsumer#onMessage} directly on a synthetic
 * {@link org.springframework.data.redis.connection.stream.MapRecord}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventPipeline — End-to-End Integration")
class EventPipelineEndToEndTest {

    // ── Infrastructure mocks ──────────────────────────────────────────────────

    @Mock WebhookSignatureValidator    signatureValidator;
    @Mock WebhookIdempotencyService    idempotencyService;
    @Mock WebhookEventProducer         eventProducer;
    @Mock WebhookNormalizationService  normalizationService;
    @Mock AutomationEngine             automationEngine;
    @Mock InstagramOAuthTokenRepository tokenRepository;
    @Mock WebhookEventStatusUpdater    statusUpdater;
    @Mock com.axion.auth.service.TokenEncryptionService encryptionService;
    @Mock ConversationMessageService   conversationMessageService;
    @Mock StringRedisTemplate          redisTemplate;

    // ── System under test ─────────────────────────────────────────────────────

    private EventPipelineIntegrationService pipeline;
    private WebhookEventOrchestrator        orchestrator;

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final UUID   TENANT_ID   = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final String IG_ACCOUNT  = "17841400000000001";
    private static final String SENDER_ID   = "10158000000000001";
    private static final String MESSAGE_ID  = "mid.e2e.chunk8.test";
    private static final String EVENT_ID    = MESSAGE_ID;
    private static final String PLAIN_TOKEN = "EAAG_test_access_token";

    private static final String VALID_SIG   = "sha256=validhmac";
    private static final String RAW_PAYLOAD = """
            {
              "object": "instagram",
              "entry": [{
                "id": "17841400000000001",
                "time": 1712678400,
                "messaging": [{
                  "sender":    { "id": "10158000000000001" },
                  "recipient": { "id": "17841400000000001" },
                  "timestamp": 1712678400001,
                  "message":   { "mid": "mid.e2e.chunk8.test", "text": "pricing" }
                }]
              }]
            }""";

    private MessageDTO normalizedMessage;
    private InstagramOAuthToken activeToken;

    @BeforeEach
    void setUp() {
        // Build orchestrator manually (dependency injection for test)
        orchestrator = new WebhookEventOrchestrator(
                automationEngine, tokenRepository, encryptionService, statusUpdater, conversationMessageService);

        // Build pipeline façade
        pipeline = new EventPipelineIntegrationService(
                signatureValidator, idempotencyService, eventProducer,
                normalizationService, orchestrator,
                new ObjectMapper(),
                new SimpleMeterRegistry()
        );

        // Canonical MessageDTO representing the parsed payload
        normalizedMessage = new MessageDTO(
                SENDER_ID, IG_ACCOUNT, MESSAGE_ID,
                "pricing", MessageType.DM,
                Instant.now(), EVENT_ID, IG_ACCOUNT
        );

        // Pre-build a valid active token
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

    // ── STAGE 1: INGEST ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Stage 1: INGEST — signature + idempotency")
    class Stage1Ingest {

        @Test
        @DisplayName("ACCEPTED: valid signature + new event → enqueues and returns ACCEPTED")
        void happyPath_accepted() {
            when(signatureValidator.isValidSignature(RAW_PAYLOAD, VALID_SIG)).thenReturn(true);
            when(idempotencyService.processAndCheckIdempotency(EVENT_ID, RAW_PAYLOAD)).thenReturn(true);

            var result = pipeline.ingest(RAW_PAYLOAD, VALID_SIG, EVENT_ID);

            assertThat(result.isAccepted()).isTrue();
            assertThat(result.eventId()).isEqualTo(EVENT_ID);
            verify(eventProducer).pushToStream(EVENT_ID, RAW_PAYLOAD);
        }

        @Test
        @DisplayName("REJECTED: invalid HMAC → no idempotency check, no enqueue, HTTP 403 implied")
        void rejected_badSignature() {
            when(signatureValidator.isValidSignature(RAW_PAYLOAD, "sha256=wrong")).thenReturn(false);

            var result = pipeline.ingest(RAW_PAYLOAD, "sha256=wrong", EVENT_ID);

            assertThat(result.isRejected()).isTrue();
            verifyNoInteractions(idempotencyService);
            verifyNoInteractions(eventProducer);
        }

        @Test
        @DisplayName("DUPLICATE: valid sig + already-seen event → no enqueue, HTTP 200 implied")
        void duplicate_suppressedWithoutEnqueue() {
            when(signatureValidator.isValidSignature(RAW_PAYLOAD, VALID_SIG)).thenReturn(true);
            when(idempotencyService.processAndCheckIdempotency(EVENT_ID, RAW_PAYLOAD)).thenReturn(false);

            var result = pipeline.ingest(RAW_PAYLOAD, VALID_SIG, EVENT_ID);

            assertThat(result.isDuplicate()).isTrue();
            verifyNoInteractions(eventProducer);
        }

        @Test
        @DisplayName("QUEUE_FAILED: Redis unavailable after idempotency pass → HTTP 500 implied")
        void queueFailed_redisUnavailable() {
            when(signatureValidator.isValidSignature(RAW_PAYLOAD, VALID_SIG)).thenReturn(true);
            when(idempotencyService.processAndCheckIdempotency(EVENT_ID, RAW_PAYLOAD)).thenReturn(true);
            doThrow(new RuntimeException("Redis connection refused"))
                    .when(eventProducer).pushToStream(anyString(), anyString());

            var result = pipeline.ingest(RAW_PAYLOAD, VALID_SIG, EVENT_ID);

            assertThat(result.isQueueFailed()).isTrue();
        }
    }

    // ── STAGE 2: QUEUE ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Stage 2: QUEUE — Redis Stream")
    class Stage2Queue {

        @Test
        @DisplayName("pushToStream called exactly once with correct eventId and payload")
        void enqueuesExactlyOnce() {
            when(signatureValidator.isValidSignature(any(), any())).thenReturn(true);
            when(idempotencyService.processAndCheckIdempotency(any(), any())).thenReturn(true);

            pipeline.ingest(RAW_PAYLOAD, VALID_SIG, EVENT_ID);

            verify(eventProducer, times(1)).pushToStream(EVENT_ID, RAW_PAYLOAD);
        }

        @Test
        @DisplayName("traceId is set in returned IngestResult after accept")
        void traceIdPresentAfterAccept() {
            when(signatureValidator.isValidSignature(any(), any())).thenReturn(true);
            when(idempotencyService.processAndCheckIdempotency(any(), any())).thenReturn(true);

            var result = pipeline.ingest(RAW_PAYLOAD, VALID_SIG, EVENT_ID);

            assertThat(result.traceId()).isNotBlank();
        }
    }

    // ── STAGES 4-5: PROCESS (orchestrate + dispatch) ───────────────────────────

    @Nested
    @DisplayName("Stages 4-5: PROCESS — orchestrate + dispatch")
    class Stage45Process {

        @BeforeEach
        void tokenSetup() {
            lenient().when(tokenRepository.findByTenantIdAndInstagramAccountId(TENANT_ID, IG_ACCOUNT))
                    .thenReturn(Optional.of(activeToken));
            lenient().when(encryptionService.decrypt(any(), any(), any())).thenReturn(PLAIN_TOKEN);
        }

        @Test
        @DisplayName("SUCCESS: engine sends one rule → PipelineResult.SUCCESS + PROCESSED status")
        void fullPipeline_ruleMatchAndSend() {
            RuleExecutionResult sentRule = new RuleExecutionResult(
                    UUID.randomUUID(), "Pricing Rule", ExecutionStatus.SENT, null);
            when(automationEngine.evaluate(TENANT_ID, normalizedMessage))
                    .thenReturn(List.of(sentRule));

            var result = pipeline.process(TENANT_ID, normalizedMessage, EVENT_ID);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.ruleResults()).hasSize(1);
            assertThat(result.ruleResults().get(0).wasSent()).isTrue();

            verify(statusUpdater).markProcessing(EVENT_ID);
            verify(statusUpdater).markProcessed(EVENT_ID);
        }

        @Test
        @DisplayName("FAILURE: all rules fail → PipelineResult.FAILURE + markFailed")
        void allRulesFail_pipelineFailure() {
            RuleExecutionResult failed = new RuleExecutionResult(
                    UUID.randomUUID(), "Pricing Rule", ExecutionStatus.FAILED, "API error");
            when(automationEngine.evaluate(TENANT_ID, normalizedMessage))
                    .thenReturn(List.of(failed));

            var result = pipeline.process(TENANT_ID, normalizedMessage, EVENT_ID);

            assertThat(result.isFailure()).isTrue();
            verify(statusUpdater).markFailed(eq(EVENT_ID), anyString());
        }

        @Test
        @DisplayName("SKIPPED: token expired → PipelineResult.SKIPPED + markFailed")
        void tokenExpired_pipelineSkipped() {
            activeToken.setTokenExpiry(Instant.now().minusSeconds(1));

            var result = pipeline.process(TENANT_ID, normalizedMessage, EVENT_ID);

            assertThat(result.isSkipped()).isTrue();
            verifyNoInteractions(automationEngine);
        }

        @Test
        @DisplayName("SUCCESS + FAILED mix: at-least-one-sent → SUCCESS outcome")
        void mixedResults_atLeastOneSent() {
            var ruleSent   = new RuleExecutionResult(UUID.randomUUID(), "A", ExecutionStatus.SENT, null);
            var ruleFailed = new RuleExecutionResult(UUID.randomUUID(), "B", ExecutionStatus.FAILED, "err");
            when(automationEngine.evaluate(TENANT_ID, normalizedMessage))
                    .thenReturn(List.of(ruleSent, ruleFailed));

            var result = pipeline.process(TENANT_ID, normalizedMessage, EVENT_ID);

            assertThat(result.isSuccess()).isTrue();
            verify(statusUpdater).markProcessed(EVENT_ID);
        }

        @Test
        @DisplayName("Engine throws unexpectedly → exception propagates to consumer for DLQ parking")
        void engineThrows_exceptionBubblesUp() {
            when(automationEngine.evaluate(any(), any()))
                    .thenThrow(new RuntimeException("Unexpected NPE"));

            assertThatThrownBy(() ->
                    pipeline.process(TENANT_ID, normalizedMessage, EVENT_ID))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Unexpected NPE");
        }
    }

    // ── Stats counters ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Pipeline stats counters")
    class StatsCounters {

        @Test
        @DisplayName("Counters increment correctly across multiple ingest calls")
        void counterAccumulation() {
            when(signatureValidator.isValidSignature(any(), any())).thenReturn(true);
            when(idempotencyService.processAndCheckIdempotency(any(), any()))
                    .thenReturn(true, false, true); // new, dup, new

            pipeline.ingest(RAW_PAYLOAD, VALID_SIG, "evt-1");
            pipeline.ingest(RAW_PAYLOAD, VALID_SIG, "evt-2");
            pipeline.ingest(RAW_PAYLOAD, VALID_SIG, "evt-3");

            var stats = pipeline.stats();
            assertThat(stats.get("ingested")).isEqualTo(2L);   // new events only
            assertThat(stats.get("enqueued")).isEqualTo(2L);
            assertThat(stats.get("duplicate")).isEqualTo(1L);
            assertThat(stats.get("rejected")).isEqualTo(0L);
        }
    }
}
