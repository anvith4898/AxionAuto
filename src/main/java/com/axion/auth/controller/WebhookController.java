package com.axion.auth.controller;

import com.axion.auth.config.MetaOAuthProperties;
import com.axion.auth.domain.dto.webhook.InstagramWebhookPayload;
import com.axion.auth.integration.DlqReplayService;
import com.axion.auth.integration.EventPipelineIntegrationService;
import com.axion.auth.integration.EventPipelineIntegrationService.IngestResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Thin HTTP adapter for the Instagram webhook pipeline.
 *
 * <h3>Chunk 8 upgrade</h3>
 * <p>The controller now delegates <em>all</em> ingest logic to
 * {@link EventPipelineIntegrationService}. This keeps the HTTP layer
 * free of business logic and makes every ingest decision observable,
 * testable, and metered at the façade layer.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET  /api/v1/webhooks/instagram}         — Meta webhook verification</li>
 *   <li>{@code POST /api/v1/webhooks/instagram}         — event ingestion</li>
 *   <li>{@code GET  /api/v1/webhooks/instagram/stats}   — pipeline counter snapshot</li>
 *   <li>{@code GET  /api/v1/webhooks/instagram/dlq}     — DLQ peek (last N entries)</li>
 *   <li>{@code POST /api/v1/webhooks/instagram/dlq/replay} — DLQ replay trigger</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/webhooks/instagram")
@RequiredArgsConstructor
public class WebhookController {

    private final MetaOAuthProperties               properties;
    private final EventPipelineIntegrationService   pipeline;
    private final DlqReplayService                  dlqReplayService;
    private final ObjectMapper                      objectMapper;

    // ── GET: Meta webhook verification ───────────────────────────────────────

    /**
     * Handles the one-time webhook verification challenge from Meta.
     * Must respond with {@code hub.challenge} echoed back within 5 seconds.
     */
    @GetMapping
    public ResponseEntity<String> verifyWebhook(
            @RequestParam(value = "hub.mode",         required = false) String mode,
            @RequestParam(value = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(value = "hub.challenge",    required = false) String challenge) {

        log.info("[webhook:VERIFY] mode={} token={}", mode, verifyToken);

        if ("subscribe".equals(mode) && properties.webhookVerifyToken().equals(verifyToken)) {
            log.info("[webhook:VERIFY] Verification successful");
            return ResponseEntity.ok(challenge);
        }

        log.warn("[webhook:VERIFY] Verification failed — unexpected mode or token");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }

    // ── POST: event ingestion ─────────────────────────────────────────────────

    /**
     * Receives, validates, deduplicates, and enqueues an inbound webhook event.
     *
     * <p>Goal: return in &lt;50ms. All heavy processing happens asynchronously
     * via the Redis Stream once this method returns.
     *
     * <p>The controller never handles domain exceptions — the pipeline façade
     * returns a typed {@link IngestResult} for every scenario.
     */
    @PostMapping
    public ResponseEntity<String> receiveEvents(
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String sigHeader,
            @RequestBody String rawPayload) {

        // Extract event ID from payload before delegating (controller owns HTTP parsing)
        String eventId = extractEventId(rawPayload);

        // Delegate to integration façade — all business logic lives there
        IngestResult result = pipeline.ingest(rawPayload, sigHeader, eventId);

        return switch (result.outcome()) {
            case ACCEPTED    -> ResponseEntity.ok("EVENT_RECEIVED");
            case DUPLICATE   -> ResponseEntity.ok("DUPLICATE");
            case REJECTED    -> ResponseEntity.status(HttpStatus.FORBIDDEN).body("Invalid signature");
            case QUEUE_FAILED -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                               .body("QUEUE_ERROR");
        };
    }

    // ── GET: pipeline stats ───────────────────────────────────────────────────

    /**
     * Returns a snapshot of pipeline counters for observability dashboards.
     *
     * <p>Example response:
     * <pre>
     * {
     *   "ingested":  142,
     *   "enqueued":  139,
     *   "rejected":    3,
     *   "duplicate":   4,
     *   "processed": 135,
     *   "failed":      4
     * }
     * </pre>
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Long>> getPipelineStats() {
        return ResponseEntity.ok(pipeline.stats());
    }

    // ── GET: DLQ peek ─────────────────────────────────────────────────────────

    /**
     * Returns the next N entries in the DLQ stream without consuming them.
     * Useful for operator inspection before deciding to replay or purge.
     *
     * @param limit number of entries to return (default 20, max 100)
     */
    @GetMapping("/dlq")
    public ResponseEntity<?> peekDlq(
            @RequestParam(defaultValue = "20") int limit) {
        log.info("[webhook:DLQ] Peek request limit={}", limit);
        return ResponseEntity.ok(dlqReplayService.peek(limit));
    }

    // ── POST: DLQ replay ──────────────────────────────────────────────────────

    /**
     * Re-adds up to {@code limit} DLQ events to the main stream for reprocessing.
     *
     * <p>Replay is idempotent — the pipeline idempotency guard will silently reject
     * any event that has already been processed.
     *
     * @param limit number of records to replay (default 10, max 50)
     */
    @PostMapping("/dlq/replay")
    public ResponseEntity<?> replayDlq(
            @RequestParam(defaultValue = "10") int limit) {
        log.info("[webhook:DLQ] Replay request limit={}", limit);
        DlqReplayService.ReplayResult result = dlqReplayService.replay(limit);
        return ResponseEntity.ok(result);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Extracts a stable event ID from the raw JSON payload for idempotency keying.
     * Falls back to a hash of the payload if no structured ID is found.
     */
    private String extractEventId(String rawPayload) {
        try {
            InstagramWebhookPayload payload =
                    objectMapper.readValue(rawPayload, InstagramWebhookPayload.class);

            if (payload != null && payload.getEntry() != null && !payload.getEntry().isEmpty()) {
                var firstEntry = payload.getEntry().get(0);
                if (firstEntry.getMessaging() != null && !firstEntry.getMessaging().isEmpty()) {
                    var first = firstEntry.getMessaging().get(0);
                    if (first.getMessage() != null && first.getMessage().getMid() != null) {
                        return first.getMessage().getMid();
                    }
                    return "messaging-" + first.getTimestamp();
                }
                if (firstEntry.getId() != null && firstEntry.getTime() != null) {
                    return firstEntry.getId() + "-" + firstEntry.getTime();
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("[webhook:INGEST] Could not parse payload for event ID extraction — using hash");
        }
        return "hash-" + rawPayload.hashCode();
    }
}
