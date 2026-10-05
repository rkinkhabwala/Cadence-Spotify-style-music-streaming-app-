package com.cadence.catalog.infrastructure.cache;

import com.cadence.catalog.application.CatalogCaches;
import com.cadence.catalog.application.CatalogViews.AlbumDetail;
import com.cadence.catalog.application.CatalogViews.ArtistDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

/**
 * Typed JSON values (no class names stored in Redis) and a 10-minute TTL. Catalog writes clear both caches after
 * their transaction commits ({@code CatalogEvents}).
 */
@Configuration(proxyBeanMethods = false)
class CatalogCacheConfig {

    static final Duration TTL = Duration.ofMinutes(10);

    @Bean
    RedisCacheManagerBuilderCustomizer catalogCaches(ObjectMapper objectMapper) {
        return builder -> builder
                .withCacheConfiguration(CatalogCaches.ARTISTS, config(objectMapper, ArtistDetail.class))
                .withCacheConfiguration(CatalogCaches.ALBUMS, config(objectMapper, AlbumDetail.class));
    }

    private static RedisCacheConfiguration config(ObjectMapper objectMapper, Class<?> type) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(TTL)
                .disableCachingNullValues()
                .prefixCacheNameWith("cadence:")
                .serializeValuesWith(SerializationPair.fromSerializer(new Jackson2JsonRedisSerializer<>(objectMapper, type)));
    }
}
