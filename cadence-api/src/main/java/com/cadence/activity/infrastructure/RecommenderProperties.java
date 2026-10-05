package com.cadence.activity.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The recommender's HTTP API (spec 3.5, D88, D89). Disabled by default: until the real recommender is connected
 * (INTEGRATION.md), every recommendation comes from the fallback.
 *
 * @param maxAttempts     total attempts per call; 1 = no retries. Only connection failures are ever retried; a 429/503
 *                        (with or without {@code Retry-After}), another error status or a timeout goes straight to the
 *                        fallback (D37)
 * @param fetchSize       items requested per call (the recommender's maximum, 50); responses are cached and cut to
 *                        each caller's limit, and the extra items absorb filtering of liked/unplayable tracks
 */
@ConfigurationProperties("cadence.recommender")
public record RecommenderProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String domain,
        Duration connectTimeout,
        Duration readTimeout,
        int maxAttempts,
        int fetchSize,
        Duration cacheTtl,
        CircuitBreaker circuitBreaker) {

    /** Resilience4j settings: count-based window, slow calls count as failures. */
    public record CircuitBreaker(
            float failureRateThreshold,
            int slidingWindowSize,
            int minimumNumberOfCalls,
            Duration slowCallDurationThreshold,
            Duration waitDurationInOpenState,
            int permittedCallsInHalfOpenState) {
    }
}
