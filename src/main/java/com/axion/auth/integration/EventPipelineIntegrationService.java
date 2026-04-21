package com.axion.auth.integration;

import com.axion.auth.domain.model.MessageDTO;
import com.axion.auth.normalization.WebhookNormalizationService;
import com.axion.auth.service.WebhookEventProducer;
import com.axion.auth.service.WebhookIdempotencyService;
import com.axion.auth.service.WebhookSignatureValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central integration façade for the AxionAuto event-driven pipeline.
 *
 * <h3>Responsibility</h3>
 * <p>This service is the <em>single named integration point</em> that binds every
 * module—webhook ingestion, queueing, normalization, orchestration, and dispatch—into
 * an observable, fault-tolerant, loosely coupled flow.
 *
 * <p>No module calls another module directly. All cross-module communication goes
 * through either this façade (for the synchronous ingest path) or the Redis Stream
 * (for the asynchronous processing path).
 *
 * <h3>Pipeline Stages</h3>
 * <pre>
 *  [STAGE 1]  INGEST   — signature validation + idempotency guard
 *  [STAGE 2]  QUEUE    — Redis Stream XADD (async boundary begins here)
 *  [STAGE 3]  CONSUME  — WebhookStreamConsumer polls stream; calls normalize()
 *  [STAGE 4]  DECIDE   — WebhookEventOrchestrator → AutomationEngine evaluates rules
 *  [STAGE 5]  SEND     — RuleReplyDispatcher → InstagramMessageSenderService
 * </pre>
 *
 * <h3>Loose coupling design</h3>
 * <ul>
 *   <li>Stages 1–2 are synchronous on the HTTP thread and complete in &lt;5ms.</li>
 *   <li>Stages 3–5 are fully asynchronous via Redis Streams — HTTP thread is released
 *       before processing begins.</li>
 *   <li>Modules communicate via value types ({@link IngestResult}, {@link WebhookEventOrchestrator.PipelineResult})
 *       rather than exceptions, so callers never need to handle leaking domain exceptions.</li>
 * </ul>
 *
 * <h3>Observability</h3>
 * <ul>
 *   <li>MDC: {@link PipelineLoggingContext} propagates traceId across the async boundary.</li>
 *   <li>Metrics: Micrometer counters for ingested, queued, rejected, and duplicate events.</li>
 *   <li>Structured log prefix: {@code [pipeline:STAGE_NAME]}.</li>
 * </ul>
 */
@Slf4j
@Service
public class EventPipelineIntegrationService {

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final WebhookSignatureValidator     signatureValidator;
    private final WebhookIdempotencyService     idempotencyService;
    private final WebhookEventProducer          eventProducer;
    private final WebhookNormalizationService   normalizationService;
    private final WebhookEventOrchestrator      orchestrator;
    private final ObjectMapper                  objectMapper;
    private final MeterRegistry                 meterRegistry;

    // ── In-process counters — initialized inline, NOT injected ───────────────

    private final AtomicLong ingestedCount  = new AtomicLong();
    private final AtomicLong enqueuedCount  = new AtomicLong();
    private final AtomicLong rejectedCount  = new AtomicLong();
    private final AtomicLong duplicateCount = new AtomicLong();
    private final AtomicLong processedCount = new AtomicLong();
    private final AtomicLong failedCount    = new AtomicLong();

    public EventPipelineIntegrationService(
            WebhookSignatureValidator   signatureValidator,
            WebhookIdempotencyService   idempotencyService,
            WebhookEventProducer        eventProducer,
            WebhookNormalizationService normalizationService,
            WebhookEventOrchestrator    orchestrator,
            ObjectMapper                objectMapper,
            MeterRegistry               meterRegistry) {
        this.signatureValidator   = signatureValidator;
        this.idempotencyService   = idempotencyService;
        this.eventProducer        = eventProducer;
        this.normalizationService = normalizationService;
        this.orchestrator         = orchestrator;
        this.objectMapper         = objectMapper;
        this.meterRegistry        = meterRegistry;
    }

    // ── Stage 1 + 2: HTTP-synchronous ingest path ─────────────────────────────

    /**
     * Validates, deduplicates, and enqueues an inbound Instagram webhook event.
     *
     * <p>This is the entry point called by {@link com.axion.auth.controller.WebhookController}
     * on every POST to {@code /api/v1/webhooks/instagram}.
     *
     * <p>Contract: this method must return in &lt;50ms so Meta does not retry.
     *
     * @param rawPayload  raw JSON body from the HTTP request
     * @param sigHeader   value of {@code X-Hub-Signature-256} header
     * @param eventId     idempotency key extracted by the controller
     * @return {@link IngestResult} — callers switch on this; no exceptions thrown
     */
    public IngestResult ingest(String rawPayload, String sigHeader, String eventId) {
        String traceId = PipelineLoggingContext.initTrace();

        try (var ctx = PipelineLoggingContext.forConsume(eventId, traceId)) {

            log.info("[pipeline:INGEST] Received eventId={} traceId={}", eventId, traceId);

            // ── STAGE 1a: Signature validation ──────────────────────────────
            if (!signatureValidator.isValidSignature(rawPayload, sigHeader)) {
                log.warn("[pipeline:INGEST] Invalid HMAC signature — rejecting eventId={}", eventId);
                rejectedCount.incrementAndGet();
                meterRegistry.counter("pipeline.events.rejected").increment();
                return IngestResult.rejected(eventId, "Invalid HMAC signature");
            }

            // ── STAGE 1b: Idempotency guard ─────────────────────────────────
            boolean isNew = idempotencyService.processAndCheckIdempotency(eventId, rawPayload);
            if (!isNew) {
                log.info("[pipeline:INGEST] Duplicate event suppressed — eventId={}", eventId);
                duplicateCount.incrementAndGet();
                meterRegistry.counter("pipeline.events.duplicate").increment();
                return IngestResult.duplicate(eventId);
            }

            ingestedCount.incrementAndGet();
            meterRegistry.counter("pipeline.events.ingested").increment();
            log.info("[pipeline:INGEST] Event accepted [eventId={}, traceId={}]", eventId, traceId);

            // ── STAGE 2: Enqueue to Redis Stream ────────────────────────────
            try {
                eventProducer.pushToStream(eventId, rawPayload);
                enqueuedCount.incrementAndGet();
                meterRegistry.counter("pipeline.events.enqueued").increment();
                log.info("[pipeline:QUEUE] Enqueued eventId={} to Redis Stream", eventId);
                return IngestResult.accepted(eventId, traceId);
            } catch (Exception e) {
                // Redis is unavailable — event is already committed to DB (RECEIVED)
                // Meta will retry; idempotency guard ensures we don't double-process.
                log.error("[pipeline:QUEUE] Failed to enqueue eventId={} — Redis unavailable", eventId, e);
                meterRegistry.counter("pipeline.events.enqueue_failed").increment();
                return IngestResult.queueFailed(eventId, e.getMessage());
            }
        }
    }

    // ── Stage 3: Normalize (called by DLQ replay or admin tooling) ────────────

    /**
     * Normalizes a raw JSON webhook payload into validated {@link MessageDTO}s.
     *
     * <p>This bypasses the ingest/queue stages and can be used for:
     * <ul>
     *   <li>DLQ replay — re-normalizing a previously failed payload</li>
     *   <li>Admin tooling — inspecting what a payload would produce</li>
     * </ul>
     *
     * @param rawPayload raw JSON string from the webhook body
     * @return list of valid, normalized {@link MessageDTO}s (may be empty)
     * @throws Exception if JSON deserialization fails
     */
    public List<MessageDTO> normalizePayload(String rawPayload) throws Exception {
        com.axion.auth.domain.dto.webhook.InstagramWebhookPayload payload =
                objectMapper.readValue(rawPayload,
                        com.axion.auth.domain.dto.webhook.InstagramWebhookPayload.class);
        return normalizationService.normalize(payload);
    }

    // ── Stage 4+5: Process (called by WebhookStreamConsumer) ─────────────────

    /**
     * Runs Stages 4–5 (orchestrate + send) for a single normalized message.
     *
     * <p>This method is a thin delegation layer that adds pipeline-level MDC context
     * and metrics before calling the orchestrator. It exists so the consumer
     * ({@link WebhookStreamConsumer}) does not need to know about metrics or
     * MDC instrumentation.
     *
     * @param tenantId  tenant that owns the IG account
     * @param message   validated, normalized {@link MessageDTO}
     * @param eventId   idempotency event ID for status tracking
     * @return {@link WebhookEventOrchestrator.PipelineResult} — never throws
     */
    public WebhookEventOrchestrator.PipelineResult process(
            UUID tenantId, MessageDTO message, String eventId) {

        try (var ctx = PipelineLoggingContext.of(
                "PROCESS", tenantId, message.igAccountId(),
                message.senderId(), message.messageId())) {

            log.info("[pipeline:PROCESS] Delegating mid={} to orchestrator [tenantId={}]",
                    message.messageId(), tenantId);

            WebhookEventOrchestrator.PipelineResult result =
                    orchestrator.process(tenantId, message, eventId);

            if (result.isSuccess()) {
                processedCount.incrementAndGet();
                meterRegistry.counter("pipeline.messages.processed").increment();
            } else if (result.isFailure()) {
                failedCount.incrementAndGet();
                meterRegistry.counter("pipeline.messages.failed").increment();
            }

            return result;
        }
    }

    // ── Stats endpoint ────────────────────────────────────────────────────────

    /**
     * Returns a snapshot of in-process pipeline counters.
     *
     * <p>Exposed via {@code GET /api/v1/webhooks/instagram/stats} for observability
     * dashboards and health checks.
     *
     * @return map of counter name → current value
     */
    public Map<String, Long> stats() {
        return Map.of(
                "ingested",  ingestedCount.get(),
                "enqueued",  enqueuedCount.get(),
                "rejected",  rejectedCount.get(),
                "duplicate", duplicateCount.get(),
                "processed", processedCount.get(),
                "failed",    failedCount.get()
        );
    }

    // ── Result types ──────────────────────────────────────────────────────────

    /**
     * Outcome of the synchronous ingest path (Stages 1–2).
     *
     * <p>The controller switches on {@link Outcome} to determine HTTP response status
     * without catching exceptions.
     */
    public record IngestResult(
            String  eventId,
            Outcome outcome,
            String  traceId,
            String  note
    ) {
        public enum Outcome { ACCEPTED, DUPLICATE, REJECTED, QUEUE_FAILED }

        static IngestResult accepted(String id, String traceId) {
            return new IngestResult(id, Outcome.ACCEPTED, traceId, null);
        }
        static IngestResult duplicate(String id) {
            return new IngestResult(id, Outcome.DUPLICATE, null, "Already processed");
        }
        static IngestResult rejected(String id, String reason) {
            return new IngestResult(id, Outcome.REJECTED, null, reason);
        }
        static IngestResult queueFailed(String id, String reason) {
            return new IngestResult(id, Outcome.QUEUE_FAILED, null, reason);
        }

        public boolean isAccepted()    { return outcome == Outcome.ACCEPTED; }
        public boolean isDuplicate()   { return outcome == Outcome.DUPLICATE; }
        public boolean isRejected()    { return outcome == Outcome.REJECTED; }
        public boolean isQueueFailed() { return outcome == Outcome.QUEUE_FAILED; }
    }

    // NOTE: The processing result type is WebhookEventOrchestrator.PipelineResult (a Java record).
    // Records are implicitly final and cannot be subclassed. Callers use this type directly.
}
