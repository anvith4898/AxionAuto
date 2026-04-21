package com.axion.auth.integration;

import com.axion.auth.domain.entity.WebhookEventEntity;
import com.axion.auth.repository.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the audit trail of a webhook event's lifecycle in the
 * {@code instagram_webhook_events} table.
 *
 * <h3>Status lifecycle</h3>
 * <pre>
 *   RECEIVED  → set by WebhookIdempotencyService on first arrival
 *   PROCESSING → set by WebhookStreamConsumer when dequeued
 *   PROCESSED  → set by WebhookEventOrchestrator on success
 *   FAILED     → set on unrecoverable pipeline error
 *   DLQ        → set when parked to dead-letter stream
 * </pre>
 *
 * <p>Status updates run in a <em>separate</em> transaction (REQUIRES_NEW) so that a
 * failure to update the status record never rolls back the actual business operation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookEventStatusUpdater {

    public static final String STATUS_RECEIVED   = "RECEIVED";
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_PROCESSED  = "PROCESSED";
    public static final String STATUS_FAILED     = "FAILED";
    public static final String STATUS_DLQ        = "DLQ";

    private final WebhookEventRepository eventRepository;

    /**
     * Transitions a webhook event to the given status.
     *
     * <p>Uses REQUIRES_NEW so this commit is independent of the caller's transaction.
     *
     * @param eventId    the idempotency event ID stored at ingestion time
     * @param newStatus  one of the STATUS_* constants defined in this class
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStatus(String eventId, String newStatus) {
        try {
            eventRepository.findByEventId(eventId).ifPresentOrElse(
                    entity -> {
                        entity.setStatus(newStatus);
                        eventRepository.save(entity);
                        log.debug("[status] eventId={} → {}", eventId, newStatus);
                    },
                    () -> log.warn("[status] No WebhookEventEntity found for eventId={} — status update skipped", eventId)
            );
        } catch (Exception e) {
            // Status-update failures must never crash the pipeline
            log.error("[status] Failed to update status for eventId={} to {}", eventId, newStatus, e);
        }
    }

    /**
     * Convenience overload: transition to PROCESSING.
     */
    public void markProcessing(String eventId) {
        updateStatus(eventId, STATUS_PROCESSING);
    }

    /**
     * Convenience overload: transition to PROCESSED.
     */
    public void markProcessed(String eventId) {
        updateStatus(eventId, STATUS_PROCESSED);
    }

    /**
     * Convenience overload: transition to FAILED with a note.
     * Stores the error reason in the status field (truncated to 50 chars for column fit).
     */
    public void markFailed(String eventId, String reason) {
        String status = STATUS_FAILED + ":" + truncate(reason, 40);
        updateStatus(eventId, status);
    }

    /**
     * Convenience overload: transition to DLQ.
     */
    public void markDlq(String eventId) {
        updateStatus(eventId, STATUS_DLQ);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
