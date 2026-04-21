package com.axion.auth.integration;

import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * MDC (Mapped Diagnostic Context) helper for the event-driven pipeline.
 *
 * <p>Each stage of the pipeline pushes its context into MDC so every log line
 * automatically carries structured fields — consumable by any structured-logging
 * aggregator (Loki, ELK, Datadog, etc.) without extra parsing.
 *
 * <h3>Fields injected</h3>
 * <pre>
 *   traceId       — random UUID scoped to this pipeline invocation
 *   tenantId      — tenant that owns the IG account
 *   igAccountId   — Instagram Business Account ID
 *   senderId      — IGSID of the inbound message author
 *   messageId     — Instagram mid
 *   stage         — pipeline stage name (CONSUME, NORMALIZE, ORCHESTRATE, DISPATCH)
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * try (var ctx = PipelineLoggingContext.of("NORMALIZE", tenantId, igAccountId, senderId, mid)) {
 *     // all log.xxx() calls here carry the above MDC fields automatically
 * }
 * }</pre>
 */
public final class PipelineLoggingContext implements AutoCloseable {

    // ── MDC key constants ─────────────────────────────────────────────────────
    public static final String TRACE_ID      = "traceId";
    public static final String TENANT_ID     = "tenantId";
    public static final String IG_ACCOUNT_ID = "igAccountId";
    public static final String SENDER_ID     = "senderId";
    public static final String MESSAGE_ID    = "messageId";
    public static final String STAGE         = "stage";

    private final Map<String, String> pushed;

    private PipelineLoggingContext(Map<String, String> fields) {
        this.pushed = Map.copyOf(fields);
        this.pushed.forEach(MDC::put);
    }

    // ── Factory methods ────────────────────────────────────────────────────────

    /** Full context — used from ORCHESTRATE stage onward when all fields are known.
     *
     * <p>Reads the existing {@code traceId} from MDC (set by an upstream {@link #initTrace()} call)
     * and includes it in the new context so the trace correlation ID is preserved across
     * the full pipeline (CONSUME → ORCHESTRATE → DISPATCH) without needing to pass it
     * as a parameter through every call frame.
     */
    public static PipelineLoggingContext of(
            String stage,
            UUID   tenantId,
            String igAccountId,
            String senderId,
            String messageId) {

        // Inherit the existing traceId from the enclosing MDC scope (set by initTrace()).
        // Without this, ORCHESTRATE/DISPATCH log lines have no traceId → undebuggable in prod.
        String inheritedTraceId = MDC.get(TRACE_ID);

        return new PipelineLoggingContext(Map.of(
                STAGE,         stage,
                TRACE_ID,      inheritedTraceId != null ? inheritedTraceId : "no-trace",
                TENANT_ID,     tenantId != null ? tenantId.toString() : "unknown",
                IG_ACCOUNT_ID, orEmpty(igAccountId),
                SENDER_ID,     orEmpty(senderId),
                MESSAGE_ID,    orEmpty(messageId)
        ));
    }

    /** Reduced context — used at CONSUME stage before full DTO is available. */
    public static PipelineLoggingContext forConsume(String eventId, String traceId) {
        return new PipelineLoggingContext(Map.of(
                STAGE,    "CONSUME",
                TRACE_ID, orEmpty(traceId),
                MESSAGE_ID, orEmpty(eventId)
        ));
    }

    /** Sets the traceId key, useful before entering a try-with-resources block. */
    public static String initTrace() {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(TRACE_ID, traceId);
        return traceId;
    }

    /**
     * Executes {@code action} within a temporary MDC context, then restores.
     * Convenience wrapper when try-with-resources is too verbose.
     */
    public static <T> T withContext(
            String stage, UUID tenantId, String igAccountId,
            String senderId, String messageId, Supplier<T> action) {

        try (var ignored = of(stage, tenantId, igAccountId, senderId, messageId)) {
            return action.get();
        }
    }

    // ── AutoCloseable ─────────────────────────────────────────────────────────

    @Override
    public void close() {
        pushed.keySet().forEach(MDC::remove);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }
}
