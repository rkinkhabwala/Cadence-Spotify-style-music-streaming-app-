package com.cadence.activity.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Recommender results per user, context and seed, in Redis for {@code cadence.recommender.cache-ttl} (spec 3.5:
 * 10 minutes). Only real recommender answers are cached, so a recovered recommender is used right away. Fails open:
 * a Redis problem just means another call.
 */
@Component
public class RecommendationCache {

    private static final Logger log = LoggerFactory.getLogger(RecommendationCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RecommenderProperties props;

    RecommendationCache(StringRedisTemplate redis, ObjectMapper objectMapper, RecommenderProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    public Optional<RecommenderClient.Result> get(UUID userId, String context, UUID seed) {
        try {
            String json = redis.opsForValue().get(key(userId, context, seed));
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, RecommenderClient.Result.class));
        } catch (Exception e) {
            log.warn("Recommendation cache read failed: {}", e.toString());
            return Optional.empty();
        }
    }

    public void put(UUID userId, String context, UUID seed, RecommenderClient.Result result) {
        try {
            redis.opsForValue().set(key(userId, context, seed), objectMapper.writeValueAsString(result), props.cacheTtl());
        } catch (Exception e) {
            log.warn("Recommendation cache write failed: {}", e.toString());
        }
    }

    static String key(UUID userId, String context, UUID seed) {
        return "cadence:recs:" + userId + ":" + context + ":" + (seed == null ? "-" : seed);
    }
}
