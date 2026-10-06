package com.cadence.streaming.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Redis-backed counters for the free plan (spec 4, D96). Like the other limiters, they fail open (D23): if Redis is
 * unavailable a skip is allowed and no ad slot is placed.
 */
@Component
public class FreePlanLimits {

    /** Outcome of a skip: {@code retryAfter} is set when refused (when the oldest skip in the window expires). */
    public record SkipDecision(boolean allowed, int remaining, Duration retryAfter) {
    }

    private static final Logger log = LoggerFactory.getLogger(FreePlanLimits.class);

    /**
     * Rolling window as a sorted set of skip ids scored by time. Drops entries older than the window; a skip id
     * already in the set is a retry and is allowed again without counting twice; otherwise the skip is recorded if
     * fewer than {@code limit} remain in the window. Returns {allowed, count, oldestScore}.
     */
    private static final RedisScript<List> SKIP = RedisScript.of("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', tonumber(ARGV[1]) - tonumber(ARGV[2]))
            if redis.call('ZSCORE', KEYS[1], ARGV[3]) then
              return {1, redis.call('ZCARD', KEYS[1]), 0}
            end
            local count = redis.call('ZCARD', KEYS[1])
            if count >= tonumber(ARGV[4]) then
              local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
              return {0, count, tonumber(oldest[2])}
            end
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[2])
            return {1, count + 1, 0}
            """, List.class);

    private final StringRedisTemplate redis;

    FreePlanLimits(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Records a skip if fewer than {@code limit} were recorded in the last {@code window}.
     *
     * @param skipId identifies the skip (the playback being skipped), so a retried request isn't counted twice
     */
    public SkipDecision skip(UUID userId, String skipId, int limit, Duration window, Instant now) {
        try {
            List<?> result = redis.execute(SKIP, List.of("cadence:skips:" + userId), Long.toString(now.toEpochMilli()),
                    Long.toString(window.toMillis()), skipId, Integer.toString(limit));
            boolean allowed = ((Number) result.get(0)).longValue() == 1;
            int count = ((Number) result.get(1)).intValue();
            if (allowed) {
                return new SkipDecision(true, Math.max(0, limit - count), null);
            }
            long oldest = ((Number) result.get(2)).longValue();
            Duration retryAfter = Duration.ofMillis(Math.max(1_000, oldest + window.toMillis() - now.toEpochMilli()));
            return new SkipDecision(false, 0, retryAfter);
        } catch (RuntimeException e) {
            log.warn("Skip limiter unavailable, allowing the skip: {}", e.toString());
            return new SkipDecision(true, limit, null);
        }
    }

    /** Counts playback starts; true for every {@code every}-th one (the ad slot placeholder). */
    public boolean adDue(UUID userId, int every) {
        if (every <= 0) {
            return false;
        }
        try {
            String key = "cadence:ads:" + userId;
            Long starts = redis.opsForValue().increment(key);
            redis.expire(key, Duration.ofDays(1));
            return starts != null && starts % every == 0;
        } catch (RuntimeException e) {
            log.warn("Ad counter unavailable, no ad slot: {}", e.toString());
            return false;
        }
    }
}
