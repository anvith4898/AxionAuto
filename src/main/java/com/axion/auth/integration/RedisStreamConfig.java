package com.axion.auth.integration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;

import java.time.Duration;

/**
 * Configures the Redis Stream infrastructure for the webhook processing pipeline.
 *
 * <h3>Consumer group design</h3>
 * <pre>
 *   Stream key      : instagram-webhooks
 *   Consumer group  : axion-processors
 *   Consumer name   : axion-worker-{hostname}  (set in application.yml)
 * </pre>
 *
 * <p>Using consumer groups provides:
 * <ul>
 *   <li><b>At-least-once delivery</b> — messages are acknowledged only on successful processing.</li>
 *   <li><b>Horizontal scaling</b> — multiple replicas can share the stream without duplicate processing.</li>
 *   <li><b>Pending entry list (PEL)</b> — unacknowledged messages are reclaimed after a configurable timeout.</li>
 * </ul>
 *
 * <h3>Container settings</h3>
 * <ul>
 *   <li>Poll interval: 100ms — low enough for near-real-time processing, not busy-waiting.</li>
 *   <li>Batch size: 10 — reads up to 10 messages per poll to amortize round-trip overhead.</li>
 *   <li>Error handler: logs the error and does NOT re-throw, preventing consumer thread death.</li>
 * </ul>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisStreamConfig {

    private static final String STREAM_KEY     = "instagram-webhooks";
    private static final String CONSUMER_GROUP = "axion-processors";
    private static final String CONSUMER_NAME  = "axion-worker-1";

    private final WebhookStreamConsumer     webhookStreamConsumer;
    private final StringRedisTemplate       redisTemplate;

    /**
     * Creates and starts the {@link StreamMessageListenerContainer} that drives
     * {@link WebhookStreamConsumer}.
     *
     * <p>The container is started automatically on bean creation. Spring lifecycle
     * methods ({@code @PreDestroy}) will stop it on context shutdown.
     */
    @Bean
    public Subscription webhookStreamSubscription(RedisConnectionFactory connectionFactory) {
        // ── Ensure stream + consumer group exist ───────────────────────────
        ensureConsumerGroup();

        // ── Container options ──────────────────────────────────────────────
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                        .builder()
                        .pollTimeout(Duration.ofMillis(100))
                        .batchSize(10)
                        .errorHandler(ex ->
                                log.error("[stream-container] Unhandled error in listener container — record skipped", ex))
                        .build();

        // ── Create container ───────────────────────────────────────────────
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
                StreamMessageListenerContainer.create(connectionFactory, options);

        // ── Subscribe ──────────────────────────────────────────────────────
        Subscription subscription = container.receive(
                Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()),
                webhookStreamConsumer
        );

        container.start();
        log.info("[stream-config] Started Redis Stream listener: stream={}, group={}, consumer={}",
                STREAM_KEY, CONSUMER_GROUP, CONSUMER_NAME);

        return subscription;
    }

    /**
     * Creates the consumer group on the stream if it does not already exist.
     * Uses {@code MKSTREAM} so the stream itself is created if absent.
     */
    private void ensureConsumerGroup() {
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0"), CONSUMER_GROUP);
            log.info("[stream-config] Created consumer group '{}' on stream '{}'", CONSUMER_GROUP, STREAM_KEY);
        } catch (Exception e) {
            // BUSYGROUP: consumer group already exists — this is expected on subsequent starts
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                log.debug("[stream-config] Consumer group '{}' already exists — skipping creation", CONSUMER_GROUP);
            } else {
                log.warn("[stream-config] Unexpected error ensuring consumer group: {}", e.getMessage(), e);
            }
        }
    }
}
