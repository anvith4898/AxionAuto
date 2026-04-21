package com.axion.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookEventProducer {

    private final StringRedisTemplate redisTemplate;
    private static final String STREAM_KEY = "instagram-webhooks";

    /**
     * Pushes the verified and deduplicated webhook payload to a Redis Stream for background processing.
     *
     * <p><b>Important:</b> on Redis failure this method intentionally re-throws.
     * The caller ({@link com.axion.auth.integration.EventPipelineIntegrationService})
     * catches this and returns HTTP 500 to Meta, which causes Meta to retry delivery.
     * Swallowing the exception here would leave the event permanently stuck in
     * status=RECEIVED with no way to re-queue it.
     *
     * @throws RuntimeException if the Redis XADD fails — callers must propagate to HTTP 500
     */
    public void pushToStream(String eventId, String rawPayload) {
        try {
            MapRecord<String, String, String> record = StreamRecords.newRecord()
                    .ofMap(Map.of(
                            "event_id", eventId != null ? eventId : "unknown",
                            "payload",  rawPayload
                    ))
                    .withStreamKey(STREAM_KEY);

            RecordId recordId = redisTemplate.opsForStream().add(record);
            log.info("[producer] Enqueued eventId={} to stream={} recordId={}", eventId, STREAM_KEY, recordId);
        } catch (Exception e) {
            log.error("[producer] CRITICAL: failed to enqueue eventId={} to Redis stream — rethrowing for HTTP 500", eventId, e);
            // Re-throw so EventPipelineIntegrationService returns IngestResult.QUEUE_FAILED
            // → HTTP 500 → Meta retries delivery → idempotency guard deduplicates safely.
            throw new RuntimeException("Redis stream write failed for eventId=" + eventId, e);
        }
    }
}
