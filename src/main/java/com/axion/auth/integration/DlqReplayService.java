package com.axion.auth.integration;

import com.axion.auth.repository.WebhookEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dead-Letter Queue (DLQ) management service.
 *
 * <h3>Overview</h3>
 * <p>Any event that cannot be processed through the main pipeline is parked to the
 * {@code instagram-webhooks-dlq} Redis Stream by one of:
 * <ul>
 *   <li>{@link WebhookStreamConsumer#parkToDlq} — for deserialization / tenant failures</li>
 *   <li>{@link com.axion.auth.normalization.WebhookNormalizationService#parkToDlq} — for validation failures</li>
 * </ul>
 *
 * <p>This service allows operators to:
 * <ol>
 *   <li><b>Peek</b> at DLQ entries without consuming them ({@link #peek(int)})</li>
 *   <li><b>Replay</b> a batch of DLQ events back to the main stream ({@link #replay(int)})</li>
 *   <li><b>Purge</b> all DLQ entries (irreversible, admin use only) ({@link #purge()})</li>
 * </ol>
 *
 * <h3>Replay mechanism</h3>
 * <p>Replay reads up to {@code limit} records from the DLQ stream (using XRANGE),
 * re-adds them to the main {@code instagram-webhooks} stream (using XADD), then
 * deletes the replayed entry from the DLQ. This is idempotent at the main pipeline
 * entry point — the {@link com.axion.auth.service.WebhookIdempotencyService} will
 * reject already-processed events before they are re-executed.
 *
 * <h3>Error handling</h3>
 * <p>Each replay record is processed independently. Failure to replay one record
 * is logged and skipped — the remaining records continue to be processed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DlqReplayService {

    public static final String DLQ_STREAM_KEY  = "instagram-webhooks-dlq";
    public static final String MAIN_STREAM_KEY = "instagram-webhooks";

    private final StringRedisTemplate    redisTemplate;
    private final WebhookEventRepository  eventRepository;
    private final MeterRegistry           meterRegistry;

    /** Micrometer gauge backing value for DLQ depth — updated every 30 s. */
    private final AtomicLong dlqDepth = new AtomicLong();

    @PostConstruct
    void registerDlqDepthGauge() {
        Gauge.builder("pipeline.dlq.depth", dlqDepth, AtomicLong::get)
                .description("Number of records currently in the instagram-webhooks-dlq stream")
                .register(meterRegistry);
    }

    /** Refreshes the DLQ depth gauge. Runs every 30 seconds. */
    @Scheduled(fixedDelay = 30_000)
    public void refreshDlqDepth() {
        try {
            Long len = redisTemplate.opsForStream().size(DLQ_STREAM_KEY);
            dlqDepth.set(len != null ? len : 0L);
        } catch (Exception e) {
            log.warn("[dlq:GAUGE] Failed to read DLQ stream length — gauge stale", e);
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Peeks at the next {@code limit} records in the DLQ without removing them.
     *
     * @param limit maximum number of records to return (capped at 100)
     * @return list of DLQ entry summaries, newest-first order
     */
    public List<DlqEntry> peek(int limit) {
        int capped = Math.min(limit, 100);
        log.info("[dlq:PEEK] Reading up to {} records from DLQ stream", capped);

        List<DlqEntry> entries = new ArrayList<>();
        try {
            List<MapRecord<String, Object, Object>> records =
                    redisTemplate.opsForStream()
                            .range(DLQ_STREAM_KEY, Range.unbounded());

            if (records == null) {
                return List.of();
            }

            records.stream()
                    .limit(capped)
                    .forEach(record -> {
                        Map<Object, Object> value = record.getValue();
                        entries.add(new DlqEntry(
                                record.getId().getValue(),
                                getString(value, "event_id"),
                                getString(value, "error_code"),
                                getString(value, "error_message")
                        ));
                    });

            log.info("[dlq:PEEK] Found {} DLQ records (showing {})",
                    records.size(), entries.size());

        } catch (Exception e) {
            log.error("[dlq:PEEK] Failed to read DLQ stream", e);
        }

        return entries;
    }

    /**
     * Replays up to {@code limit} DLQ records back to the main stream.
     *
     * <p>Each successfully re-added record is deleted from the DLQ.
     * Already-processed events will be silently rejected by the idempotency guard.
     *
     * @param limit maximum number of records to replay (capped at 50)
     * @return {@link ReplayResult} describing how many records were replayed vs. failed
     */
    public ReplayResult replay(int limit) {
        int capped = Math.min(limit, 50);
        log.info("[dlq:REPLAY] Starting replay of up to {} DLQ records", capped);

        int replayed = 0;
        int failed   = 0;

        try {
            List<MapRecord<String, Object, Object>> records =
                    redisTemplate.opsForStream()
                            .range(DLQ_STREAM_KEY, Range.unbounded());

            if (records == null || records.isEmpty()) {
                log.info("[dlq:REPLAY] DLQ is empty — nothing to replay");
                return new ReplayResult(0, 0, "DLQ is empty");
            }

            List<MapRecord<String, Object, Object>> batch = records.stream()
                    .limit(capped)
                    .toList();

            for (MapRecord<String, Object, Object> dlqRecord : batch) {
                String eventId = getString(dlqRecord.getValue(), "event_id");
                try {
                    replayOne(dlqRecord, eventId);
                    replayed++;
                } catch (Exception e) {
                    log.error("[dlq:REPLAY] Failed to replay eventId={} — skipping", eventId, e);
                    failed++;
                }
            }

        } catch (Exception e) {
            log.error("[dlq:REPLAY] Failed to read DLQ stream for replay", e);
        }

        log.info("[dlq:REPLAY] Complete: replayed={} failed={} from DLQ", replayed, failed);
        return new ReplayResult(replayed, failed, null);
    }

    /**
     * Purges all records from the DLQ stream using XTRIM (MAXLEN 0).
     *
     * <p><b>WARNING:</b> this is irreversible. Use only when DLQ entries are
     * confirmed stale or have been manually investigated.
     *
     * @return number of records trimmed
     */
    public long purge() {
        log.warn("[dlq:PURGE] Purging all records from DLQ stream '{}'", DLQ_STREAM_KEY);
        try {
            Long trimmed = redisTemplate.opsForStream().trim(DLQ_STREAM_KEY, 0);
            long count = trimmed != null ? trimmed : 0L;
            log.warn("[dlq:PURGE] Purged {} DLQ records", count);
            return count;
        } catch (Exception e) {
            log.error("[dlq:PURGE] Failed to purge DLQ stream", e);
            return 0L;
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private void replayOne(MapRecord<String, Object, Object> dlqRecord, String eventId) {
        // ── Fetch original payload from DB ─────────────────────────────────────
        // The DLQ only stores event_id + error metadata; the full raw payload must be
        // retrieved from WebhookEventEntity. Without this, we replay an empty string
        // which the consumer immediately parks back to DLQ — creating an infinite loop.
        String payload = eventRepository.findByEventId(eventId)
                .map(entity -> entity.getPayload())
                .orElse(null);

        if (payload == null || payload.isBlank()) {
            log.warn("[dlq:REPLAY] No payload found in DB for eventId={} — skipping (cannot replay without payload)",
                    eventId);
            // Remove from DLQ to avoid repeated replay attempts for unresolvable events
            redisTemplate.opsForStream().delete(DLQ_STREAM_KEY, dlqRecord.getId().getValue());
            return;
        }

        // ── Re-add to main stream with the real payload ────────────────────────
        // The idempotency guard will reject already-processed events before any
        // business logic runs — so replay is safe to call multiple times.
        MapRecord<String, String, String> replayRecord = StreamRecords.newRecord()
                .ofMap(Map.of(
                        "event_id", eventId,
                        "payload",  payload,
                        "replay",   "true"
                ))
                .withStreamKey(MAIN_STREAM_KEY);

        redisTemplate.opsForStream().add(replayRecord);
        log.info("[dlq:REPLAY] Re-added eventId={} with real payload ({} bytes) to main stream",
                eventId, payload.length());

        // ── Remove from DLQ after successful re-add ────────────────────────────
        redisTemplate.opsForStream().delete(DLQ_STREAM_KEY, dlqRecord.getId().getValue());
        log.debug("[dlq:REPLAY] Deleted dlqRecordId={} from DLQ", dlqRecord.getId().getValue());
    }

    private static String getString(Map<Object, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }

    // ── Value types ────────────────────────────────────────────────────────────

    /**
     * A single DLQ stream entry, ready for display or replay decision.
     */
    public record DlqEntry(
            String streamRecordId,
            String eventId,
            String errorCode,
            String errorMessage
    ) {}

    /**
     * Summary of a replay operation.
     */
    public record ReplayResult(
            int    replayed,
            int    failed,
            String note
    ) {}
}
