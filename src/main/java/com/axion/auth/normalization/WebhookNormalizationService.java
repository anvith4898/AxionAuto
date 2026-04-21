package com.axion.auth.normalization;

import com.axion.auth.domain.dto.webhook.InstagramWebhookPayload;
import com.axion.auth.domain.model.MessageDTO;
import com.axion.auth.normalization.MessageDTOValidator.ValidationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full normalization pipeline:
 *
 * <pre>
 *   Raw InstagramWebhookPayload
 *         │
 *         ▼
 *   WebhookPayloadParser      ← null-safe parsing, type resolution, echo filtering
 *         │
 *         ▼ List&lt;MessageDTO&gt;
 *         │
 *   MessageDTOValidator       ← business rule validation (6 rules)
 *         │
 *    ─────┴──────────
 *    │               │
 *  VALID          INVALID
 *    │               │
 *    ▼               ▼
 *  return        Redis DLQ stream (instagram-webhooks-dlq)
 * </pre>
 *
 * <h3>Observability</h3>
 * <p>Every normalization run increments Micrometer counters:
 * <ul>
 *   <li>{@code webhook.normalization.parsed} — total candidate DTOs parsed</li>
 *   <li>{@code webhook.normalization.valid} — DTOs that passed all validation rules</li>
 *   <li>{@code webhook.normalization.invalid} — DTOs parked to DLQ, tagged by {@code error_code}</li>
 * </ul>
 *
 * <h3>DLQ record fields</h3>
 * <p>Each DLQ entry contains: {@code event_id}, {@code error_code}, {@code error_message},
 * {@code ig_account_id}, {@code message_type}, and {@code sender_id} for debugging.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookNormalizationService {

    private static final String DLQ_STREAM_KEY = "instagram-webhooks-dlq";

    // ── Micrometer metric names ───────────────────────────────────────────────
    private static final String METRIC_PARSED   = "webhook.normalization.parsed";
    private static final String METRIC_VALID    = "webhook.normalization.valid";
    private static final String METRIC_INVALID  = "webhook.normalization.invalid";
    private static final String TAG_ERROR_CODE  = "error_code";
    private static final String TAG_MSG_TYPE    = "message_type";

    // ── MDC key ───────────────────────────────────────────────────────────────
    private static final String MDC_IG_ACCOUNT = "igAccountId";

    private final WebhookPayloadParser   parser;
    private final MessageDTOValidator    validator;
    private final StringRedisTemplate    redisTemplate;
    private final MeterRegistry          meterRegistry;

    /**
     * Normalizes a raw webhook payload into validated {@link MessageDTO}s.
     *
     * <p>Invalid DTOs are parked to the DLQ stream and excluded from the returned list.
     * Callers receive only actionable, fully validated messages.
     *
     * @param payload the deserialized Instagram webhook payload
     * @return immutable list of valid, normalized {@link MessageDTO}s (may be empty)
     */
    public List<MessageDTO> normalize(InstagramWebhookPayload payload) {
        // Extract igAccountId for MDC correlation (best-effort — may be null)
        String igAccountId = extractIgAccountId(payload);

        try (var ignored = pushMdc(igAccountId)) {

            // Step 1: Parse raw payload into candidate DTOs
            List<MessageDTO> candidates = parser.parse(payload);

            if (candidates.isEmpty()) {
                return List.of();
            }

            // Record total parsed count
            Counter.builder(METRIC_PARSED)
                    .register(meterRegistry)
                    .increment(candidates.size());

            // Step 2: Validate each candidate; park invalids to DLQ
            List<MessageDTO> valid = candidates.stream()
                    .map(validator::validate)
                    .peek(result -> {
                        if (!result.valid()) {
                            parkToDlq(result);
                            Counter.builder(METRIC_INVALID)
                                    .tag(TAG_ERROR_CODE,  orEmpty(result.errorCode()))
                                    .tag(TAG_MSG_TYPE,    result.dto() != null
                                            ? result.dto().messageType().name() : "unknown")
                                    .register(meterRegistry)
                                    .increment();
                        }
                    })
                    .filter(ValidationResult::valid)
                    .map(ValidationResult::dto)
                    .toList(); // Java 16+ immutable list

            // Record valid count
            Counter.builder(METRIC_VALID)
                    .register(meterRegistry)
                    .increment(valid.size());

            log.info("[normalization] Complete: {}/{} DTOs valid, {} parked to DLQ [igAccountId={}]",
                    valid.size(), candidates.size(), candidates.size() - valid.size(), igAccountId);

            return valid;
        }
    }

    // ── DLQ parking ──────────────────────────────────────────────────────────

    /**
     * Parks a failed {@link ValidationResult} to the Redis DLQ stream.
     * Failures here are logged but do not propagate — DLQ write is best-effort.
     *
     * <p>The DLQ record includes {@code ig_account_id}, {@code sender_id}, and
     * {@code message_type} in addition to the error fields, enabling operators to
     * triage and replay events without re-running the full pipeline.
     */
    private void parkToDlq(ValidationResult result) {
        MessageDTO dto    = result.dto();
        String rawEventId = dto != null ? dto.rawEventId() : "unknown";

        log.warn("[normalization] Parking invalid MessageDTO to DLQ [eventId={}, code={}, reason={}]",
                rawEventId, result.errorCode(), result.errorMessage());

        try {
            Map<String, String> fields = new HashMap<>();
            fields.put("event_id",      rawEventId);
            fields.put("error_code",    orEmpty(result.errorCode()));
            fields.put("error_message", orEmpty(result.errorMessage()));

            // Include diagnostic fields so ops can triage without re-running the pipeline
            if (dto != null) {
                fields.put("ig_account_id", dto.igAccountId());
                fields.put("sender_id",      dto.senderId());
                fields.put("message_type",   dto.messageType().name());
                fields.put("is_deleted",     String.valueOf(dto.isDeleted()));
                fields.put("has_attachment", String.valueOf(dto.hasAttachment()));
            }

            MapRecord<String, String, String> record = StreamRecords.newRecord()
                    .ofMap(fields)
                    .withStreamKey(DLQ_STREAM_KEY);

            redisTemplate.opsForStream().add(record);

        } catch (Exception e) {
            // DLQ write failure must NEVER fail the main normalization pipeline
            log.error("[normalization] Failed to park event {} to DLQ stream — event will be silently dropped",
                    rawEventId, e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private interface MdcCloseable extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Pushes the igAccountId into MDC for structured log correlation.
     * Returns an {@link MdcCloseable} so it can be used in try-with-resources.
     */
    private MdcCloseable pushMdc(String igAccountId) {
        if (igAccountId != null && !igAccountId.isBlank()) {
            MDC.put(MDC_IG_ACCOUNT, igAccountId);
        }
        return () -> MDC.remove(MDC_IG_ACCOUNT);
    }

    /**
     * Best-effort extraction of igAccountId from the payload for MDC.
     * Returns null if the payload or first entry is null.
     */
    private static String extractIgAccountId(InstagramWebhookPayload payload) {
        if (payload == null || payload.getEntry() == null || payload.getEntry().isEmpty()) {
            return null;
        }
        var firstEntry = payload.getEntry().get(0);
        return firstEntry != null ? firstEntry.getId() : null;
    }

    private static String orEmpty(String s) {
        return s != null ? s : "";
    }
}
