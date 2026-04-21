package com.axion.auth.integration;

import com.axion.auth.domain.dto.webhook.InstagramWebhookPayload;
import com.axion.auth.domain.entity.InstagramOAuthToken;
import com.axion.auth.domain.model.MessageDTO;
import com.axion.auth.domain.repository.InstagramOAuthTokenRepository;
import com.axion.auth.normalization.WebhookNormalizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Redis Stream consumer that drives the automation pipeline.
 *
 * <h3>Flow per consumed record</h3>
 * <pre>
 *  Redis Stream record (event_id + payload)
 *       │
 *       ▼
 *  Deserialize JSON → InstagramWebhookPayload
 *       │
 *       ▼
 *  WebhookNormalizationService.normalize()
 *       │  valid List&lt;MessageDTO&gt;
 *       ▼
 *  for each MessageDTO:
 *       WebhookEventOrchestrator.process(tenantId, message, eventId)
 *       │
 *       ▼
 *  On unrecoverable error → park to DLQ stream
 * </pre>
 *
 * <h3>Tenant resolution</h3>
 * <p>The inbound payload contains the Instagram account ID ({@code igAccountId}).
 * We resolve the owning tenant by looking up the connected OAuth token for that account.
 * This design means no tenant header is needed in the webhook payload itself —
 * the token store serves as the account→tenant registry.
 *
 * <h3>Error handling strategy</h3>
 * <ul>
 *   <li><b>Deserialization failure</b>: log + park to DLQ, ack the record (avoid infinite retry).</li>
 *   <li><b>Tenant not found</b>: log + park to DLQ, ack.</li>
 *   <li><b>Normalization produces 0 valid DTOs</b>: log + ack (may be a status-only webhook).</li>
 *   <li><b>Orchestrator failure</b>: orchestrator updates event status; consumer acks.</li>
 *   <li><b>Transient infrastructure errors</b>: throw — the container will retry the record.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookStreamConsumer implements StreamListener<String, MapRecord<String, String, String>> {

    private static final String DLQ_STREAM_KEY = "instagram-webhooks-dlq";

    private final ObjectMapper                  objectMapper;
    private final WebhookNormalizationService   normalizationService;
    private final WebhookEventOrchestrator      orchestrator;
    private final InstagramOAuthTokenRepository tokenRepository;
    private final StringRedisTemplate           redisTemplate;

    // ── StreamListener contract ────────────────────────────────────────────────

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        String eventId  = record.getValue().getOrDefault("event_id", "unknown");
        String payload  = record.getValue().get("payload");
        String traceId  = PipelineLoggingContext.initTrace();

        try (var ctx = PipelineLoggingContext.forConsume(eventId, traceId)) {
            log.info("[consumer] Dequeued webhook record id={} eventId={}", record.getId(), eventId);

            if (payload == null || payload.isBlank()) {
                log.warn("[consumer] Received empty payload for eventId={} — parking to DLQ", eventId);
                parkToDlq(eventId, "EMPTY_PAYLOAD", "Payload was null or blank");
                return;
            }

            // ── 1. Deserialize ──────────────────────────────────────────────
            InstagramWebhookPayload webhookPayload;
            try {
                webhookPayload = objectMapper.readValue(payload, InstagramWebhookPayload.class);
            } catch (Exception e) {
                log.error("[consumer] JSON deserialization failed for eventId={}", eventId, e);
                parkToDlq(eventId, "DESERIALIZATION_ERROR", e.getMessage());
                return;
            }

            // ── 2. Normalize ────────────────────────────────────────────────
            List<MessageDTO> validMessages;
            try {
                validMessages = normalizationService.normalize(webhookPayload);
            } catch (Exception e) {
                log.error("[consumer] Normalization threw unexpectedly for eventId={}", eventId, e);
                parkToDlq(eventId, "NORMALIZATION_ERROR", e.getMessage());
                return;
            }

            if (validMessages.isEmpty()) {
                log.info("[consumer] No actionable messages after normalization for eventId={} — skipping", eventId);
                return;
            }

            log.info("[consumer] {} valid message(s) normalized from eventId={}", validMessages.size(), eventId);

            // ── 3. Resolve tenant and orchestrate each message ──────────────
            for (MessageDTO message : validMessages) {
                processMessage(message, eventId);
            }
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void processMessage(MessageDTO message, String eventId) {
        try (var ctx = PipelineLoggingContext.of(
                "DISPATCH", null, message.igAccountId(), message.senderId(), message.messageId())) {

            // Resolve the tenant for this IG account from the OAuth token store
            UUID tenantId = resolveTenant(message.igAccountId());
            if (tenantId == null) {
                log.warn("[consumer] No tenant found for igAccountId={} — parking to DLQ", message.igAccountId());
                parkToDlq(eventId, "NO_TENANT", "igAccountId=" + message.igAccountId());
                return;
            }

            WebhookEventOrchestrator.PipelineResult result =
                    orchestrator.process(tenantId, message, eventId);

            if (result.isSuccess()) {
                log.info("[consumer] Pipeline succeeded for mid={} tenantId={}", message.messageId(), tenantId);
            } else if (result.isSkipped()) {
                log.info("[consumer] Pipeline skipped for mid={}: {}", message.messageId(), result.note());
            } else {
                log.warn("[consumer] Pipeline failed for mid={}: {}", message.messageId(), result.note());
            }

        } catch (Exception e) {
            // Unexpected error — log with full context, but do not re-throw.
            // The event status is updated by the orchestrator; we ack here to avoid stream log growth.
            log.error("[consumer] Unexpected error processing mid={} from igAccountId={}",
                    message.messageId(), message.igAccountId(), e);
            parkToDlq(eventId, "UNEXPECTED_ERROR", e.getMessage());
        }
    }

    /**
     * Resolves the tenant ID that owns the given Instagram account via an indexed lookup.
     *
     * <p><b>Performance note:</b> this uses a single indexed query on
     * {@code (instagram_account_id, status)} rather than the previous {@code findAll()} scan.
     * The old approach loaded all tokens into memory — catastrophic at scale.
     *
     * @return the tenant UUID, or {@code null} if no ACTIVE token found for this account
     */
    private UUID resolveTenant(String igAccountId) {
        return tokenRepository
                .findFirstByInstagramAccountIdAndStatus(
                        igAccountId, InstagramOAuthToken.TokenStatus.ACTIVE)
                .map(InstagramOAuthToken::getTenantId)
                .orElse(null);
    }

    /**
     * Parks a failed event to the DLQ Redis stream.
     * Best-effort — a failure here is logged but never re-thrown.
     */
    private void parkToDlq(String eventId, String errorCode, String errorMessage) {
        try {
            MapRecord<String, String, String> dlqRecord = StreamRecords.newRecord()
                    .ofMap(Map.of(
                            "event_id",      eventId,
                            "error_code",    errorCode != null ? errorCode : "UNKNOWN",
                            "error_message", errorMessage != null ? errorMessage : ""
                    ))
                    .withStreamKey(DLQ_STREAM_KEY);

            redisTemplate.opsForStream().add(dlqRecord);
            log.info("[consumer] Parked eventId={} to DLQ with code={}", eventId, errorCode);
        } catch (Exception dlqEx) {
            log.error("[consumer] Failed to park eventId={} to DLQ — event silently dropped", eventId, dlqEx);
        }
    }
}
