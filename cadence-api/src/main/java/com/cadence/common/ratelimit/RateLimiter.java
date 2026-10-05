package com.cadence.common.ratelimit;

import com.cadence.common.error.TooManyRequestsException;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Distributed token-bucket limiter (Bucket4j on Redis), shared by all API instances.
 * Fails open: if Redis is unavailable the request is allowed and a warning is logged.
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final ProxyManager<String> buckets;
    private final RateLimitProperties properties;

    public RateLimiter(ProxyManager<String> buckets, RateLimitProperties properties) {
        this.buckets = buckets;
        this.properties = properties;
    }

    /**
     * Consumes one token from the {@code limitName} bucket of {@code subject} (an IP, a user id, ...).
     *
     * @throws TooManyRequestsException when the bucket is empty
     */
    public void consume(String limitName, String subject) {
        RateLimitProperties.Limit limit = properties.rateLimits().get(limitName);
        if (limit == null) {
            throw new IllegalArgumentException("No rate limit named " + limitName);
        }
        ConsumptionProbe probe;
        try {
            BucketConfiguration configuration = BucketConfiguration.builder()
                    .addLimit(l -> l.capacity(limit.capacity()).refillIntervally(limit.capacity(), limit.period()))
                    .build();
            probe = buckets.builder()
                    .build("rate-limit:" + limitName + ":" + subject, () -> configuration)
                    .tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, allowing request ({}): {}", limitName, e.toString());
            return;
        }
        if (!probe.isConsumed()) {
            long seconds = (long) Math.ceil(probe.getNanosToWaitForRefill() / (double) TimeUnit.SECONDS.toNanos(1));
            throw new TooManyRequestsException("rate-limited", "Too many requests; retry in " + seconds + "s", seconds);
        }
    }
}
