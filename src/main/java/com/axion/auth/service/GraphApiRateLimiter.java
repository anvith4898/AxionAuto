package com.axion.auth.service;

import com.axion.auth.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

/**
 * Per-account sliding-window rate limiter for Meta Graph API send-message calls.
 *
 * <h3>Why sliding window?</h3>
 * <p>A fixed epoch-second key (the previous implementation) allows a "boundary burst":
 * 200 requests in the last 10ms of second T, then 200 more in the first 10ms of T+1 —
 * 400 requests in 20ms. A sliding window prevents this by always measuring the
 * trailing {@code windowSeconds} period.
 *
 * <h3>Atomicity via Lua script</h3>
 * <p>The previous implementation used four separate Redis calls
 * (ZREMRANGEBYSCORE, ZADD, EXPIRE, ZCARD). Under concurrent load from multiple virtual
 * threads, two threads could both read a count of 199, both add their entry, and both
 * proceed — resulting in 201 requests reaching Meta. This is the TOCTOU (time-of-check
 * time-of-use) race condition.
 *
 * <p>The fix uses a single <b>Lua script</b> evaluated atomically by Redis. All five
 * operations execute in one round-trip with no interleaving from other clients.
 *
 * <h3>Member uniqueness</h3>
 * <p>The Lua script appends a random suffix to each member string
 * ({@code epochMs:random}) to guarantee uniqueness even when two calls arrive within
 * the same millisecond. Previously, sharing the same epoch-ms member caused a silent
 * {@code ZADD} overwrite, undercounting usage.
 *
 * <h3>Backoff warning threshold</h3>
 * <p>A warning is emitted when usage reaches {@code warningThresholdPct}% of the limit
 * (default 80%). This allows operators to pro-actively alert on approaching limits
 * before requests are rejected.
 *
 * <h3>Rate limits</h3>
 * <p>Instagram Graph API enforces ~200 send-message calls per hour per access token.
 * Default: 200/hour (configurable via {@code axion.meta.rate-limit.requests-per-hour}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphApiRateLimiter {

    /** Cryptographically strong RNG — avoids calendar-epoch collisions under concurrent load. */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final StringRedisTemplate redisTemplate;

    /** Maximum calls allowed within the rolling window. Default: 200/hour (Instagram's DM limit). */
    @Value("${axion.meta.rate-limit.requests-per-hour:200}")
    private int maxRequestsPerWindow;

    /** Rolling window in seconds. Default: 3600 (1 hour). */
    @Value("${axion.meta.rate-limit.window-seconds:3600}")
    private int windowSeconds;

    /**
     * Percentage of the limit at which a WARN log is emitted.
     * At 80% the operator can see pressure before requests are rejected.
     */
    @Value("${axion.meta.rate-limit.warning-threshold-pct:80}")
    private int warningThresholdPct;

    // ── Atomic Lua script ─────────────────────────────────────────────────────

    /**
     * Atomic sliding-window check-and-increment, executed as a single Redis transaction.
     *
     * <pre>
     * KEYS[1]  = sorted-set key (rate_limit:ig_account:{id})
     * ARGV[1]  = nowMs      (epoch milliseconds as string)
     * ARGV[2]  = cutoffMs   (nowMs - windowMs)
     * ARGV[3]  = member     (unique: "nowMs:randomHex")
     * ARGV[4]  = ttlSeconds (window + 10 for self-cleaning)
     * ARGV[5]  = maxLimit   (upper bound)
     *
     * Returns: current count after add (long), or -1 if over limit (rolled back).
     * </pre>
     */
    private static final DefaultRedisScript<Long> SLIDING_WINDOW_SCRIPT =
            new DefaultRedisScript<>("""
            local key     = KEYS[1]
            local nowMs   = tonumber(ARGV[1])
            local cutoff  = tonumber(ARGV[2])
            local member  = ARGV[3]
            local ttl     = tonumber(ARGV[4])
            local limit   = tonumber(ARGV[5])
            
            -- 1. Evict entries older than the window
            redis.call('ZREMRANGEBYSCORE', key, 0, cutoff)
            
            -- 2. Tentatively add this request (unique member prevents collision)
            redis.call('ZADD', key, nowMs, member)
            
            -- 3. Refresh TTL so the key self-cleans
            redis.call('EXPIRE', key, ttl)
            
            -- 4. Count requests in window
            local count = redis.call('ZCARD', key)
            
            -- 5. If over limit, rollback and signal rejection
            if count > limit then
                redis.call('ZREM', key, member)
                return -1
            end
            
            return count
            """, Long.class);

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Atomically checks the sliding window and reserves a slot for this request.
     *
     * <p>If the limit is exceeded, the request is <em>not</em> counted (the tentative
     * add is rolled back inside the Lua script) and a {@link RateLimitExceededException}
     * is thrown. The caller's Resilience4j retry will wait before trying again.
     *
     * @param instagramAccountId the IG Business Account ID (per-account bucket)
     * @throws RateLimitExceededException if the 200/hour limit is reached
     */
    public void acquireOrThrow(String instagramAccountId) {
        long nowMs    = Instant.now().toEpochMilli();
        long windowMs = (long) windowSeconds * 1_000L;
        long cutoffMs = nowMs - windowMs;

        // Unique member: "epochMs:random8bytes" — avoids collision when two calls share the same ms
        byte[] randomBytes = new byte[8];
        SECURE_RANDOM.nextBytes(randomBytes);
        String member = nowMs + ":" + bytesToHex(randomBytes);
        String key    = "rate_limit:ig_account:" + instagramAccountId;

        Long count = redisTemplate.execute(
                SLIDING_WINDOW_SCRIPT,
                List.of(key),
                String.valueOf(nowMs),
                String.valueOf(cutoffMs),
                member,
                String.valueOf(windowSeconds + 10L),
                String.valueOf(maxRequestsPerWindow)
        );

        // count == -1 → over limit (rollback was performed inside Lua)
        if (count == null || count == -1L) {
            log.warn("[rate-limiter] Rate limit EXCEEDED for igAccountId={}: {}/{} used in {}s window",
                    instagramAccountId, maxRequestsPerWindow, maxRequestsPerWindow, windowSeconds);

            throw new RateLimitExceededException(
                    "Rate limit exceeded for Instagram Account " + instagramAccountId
                    + " — " + maxRequestsPerWindow + "/" + maxRequestsPerWindow
                    + " requests used in the last " + windowSeconds + "s.");
        }

        // Warn when approaching the limit so operators can alert before hard rejection
        long warningThreshold = (long) (maxRequestsPerWindow * warningThresholdPct / 100.0);
        if (count >= warningThreshold) {
            log.warn("[rate-limiter] Approaching rate limit for igAccountId={}: {}/{} (>{}% threshold)",
                    instagramAccountId, count, maxRequestsPerWindow, warningThresholdPct);
        } else {
            log.debug("[rate-limiter] igAccountId={} usage={}/{} in rolling {}s window",
                    instagramAccountId, count, maxRequestsPerWindow, windowSeconds);
        }
    }

    /**
     * Returns the current usage count for an account without incrementing.
     * Useful for dashboard/debug endpoints.
     *
     * <p><b>Note:</b> This is a point-in-time read. Stale entries older than the window
     * are excluded from the count by filtering the score range in Redis, but no eviction
     * is performed — {@code currentUsage} is intentionally read-only and has no
     * side-effects on the sorted set.
     *
     * @param instagramAccountId the account to inspect
     * @return number of requests in the current window (0 if key doesn't exist)
     */
    public long currentUsage(String instagramAccountId) {
        long nowMs    = Instant.now().toEpochMilli();
        long cutoffMs = nowMs - ((long) windowSeconds * 1_000L);
        String key    = "rate_limit:ig_account:" + instagramAccountId;

        // Count only entries whose score (epochMs) falls within the current window.
        // ZCOUNT is O(log N) and does NOT mutate the set.
        Long count = redisTemplate.opsForZSet().count(key, cutoffMs, nowMs);
        return count != null ? count : 0L;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /** Converts a raw byte array to a lowercase hex string for member uniqueness. */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
