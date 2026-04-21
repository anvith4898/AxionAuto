package com.axion.auth.service;

import com.axion.auth.domain.entity.WebhookEventEntity;
import com.axion.auth.repository.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookIdempotencyService {

    private final StringRedisTemplate redisTemplate;
    private final WebhookEventRepository eventRepository;

    private static final String IDEMPOTENCY_PREFIX = "webhook:processed:";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    /**
     * Checks if the event has already been processed using Redis SETNX and DB unique constraints.
     * Returns true if it's a new event, false if it's a duplicate.
     */
    @Transactional
    public boolean processAndCheckIdempotency(String eventId, String rawPayload) {
        if (eventId == null || eventId.trim().isEmpty()) {
            // Can't deduplicate without an ID, so we process it anyway, or assume it's new
            return true;
        }

        String redisKey = IDEMPOTENCY_PREFIX + eventId;
        
        // 1. Fast path: Redis SETNX
        Boolean isNewInRedis = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", IDEMPOTENCY_TTL);
        if (Boolean.FALSE.equals(isNewInRedis)) {
            log.debug("Duplicate event caught by Redis: {}", eventId);
            return false;
        }

        // 2. Slow path: DB Unique Constraint
        if (eventRepository.existsByEventId(eventId)) {
            log.debug("Duplicate event caught by DB exists check: {}", eventId);
            return false;
        }

        try {
            WebhookEventEntity entity = WebhookEventEntity.builder()
                    .eventId(eventId)
                    .payload(rawPayload)
                    .status("RECEIVED")
                    .build();
            eventRepository.save(entity);
            return true; // Successfully persisted, it's a new event
        } catch (DataIntegrityViolationException e) {
            log.debug("Duplicate event caught by DB unique constraint during insert: {}", eventId);
            return false;
        }
    }
}
