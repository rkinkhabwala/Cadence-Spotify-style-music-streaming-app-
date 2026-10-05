package com.cadence.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Configuration;

/**
 * Spring caching backed by Redis (spring.cache.type=redis). Fails open like the rate limiter: if Redis is unavailable a
 * read is a miss and writes/evictions are skipped with a warning, so the API keeps serving from Postgres.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                warn("read", cache, e);
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                warn("write", cache, e);
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                warn("evict", cache, e);
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                warn("clear", cache, e);
            }
        };
    }

    private static void warn(String operation, Cache cache, RuntimeException e) {
        log.warn("Cache {} failed on {}, continuing without cache: {}", operation, cache.getName(), e.toString());
    }
}
