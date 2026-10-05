package com.cadence.common.ratelimit;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

    @Bean(destroyMethod = "shutdown")
    RedisClient rateLimitRedisClient(RedisConnectionDetails details) {
        RedisConnectionDetails.Standalone standalone = details.getStandalone();
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(standalone.getHost())
                .withPort(standalone.getPort())
                .withDatabase(standalone.getDatabase())
                .withTimeout(Duration.ofMillis(500));
        if (details.getPassword() != null && !details.getPassword().isEmpty()) {
            if (details.getUsername() != null) {
                uri.withAuthentication(details.getUsername(), details.getPassword());
            } else {
                uri.withPassword(details.getPassword().toCharArray());
            }
        }
        return RedisClient.create(uri.build());
    }

    @Bean(destroyMethod = "close")
    StatefulRedisConnection<String, byte[]> rateLimitRedisConnection(RedisClient rateLimitRedisClient) {
        return rateLimitRedisClient.connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
    }

    @Bean
    RateLimiter rateLimiter(StatefulRedisConnection<String, byte[]> rateLimitRedisConnection,
                            RateLimitProperties properties) {
        Duration longest = properties.rateLimits().values().stream()
                .map(RateLimitProperties.Limit::period).max(Duration::compareTo).orElse(Duration.ofMinutes(1));
        ProxyManager<String> buckets = Bucket4jLettuce.casBasedBuilder(rateLimitRedisConnection)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(longest))
                .build();
        return new RateLimiter(buckets, properties);
    }
}
