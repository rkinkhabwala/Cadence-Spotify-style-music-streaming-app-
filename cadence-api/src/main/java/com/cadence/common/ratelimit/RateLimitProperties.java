package com.cadence.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/** Named limits, e.g. {@code cadence.rate-limits.login.capacity=5}, {@code ...login.period=1m}. */
@ConfigurationProperties("cadence")
public record RateLimitProperties(Map<String, Limit> rateLimits) {

    public record Limit(long capacity, Duration period) {
    }

    public RateLimitProperties {
        rateLimits = rateLimits == null ? Map.of() : Map.copyOf(rateLimits);
    }
}
