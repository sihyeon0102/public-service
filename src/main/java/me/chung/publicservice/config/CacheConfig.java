package me.chung.publicservice.config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);
    private static final long WARNING_INTERVAL_NANOS = Duration.ofSeconds(30).toNanos();
    private static final AtomicLong nextWarningNanos = new AtomicLong();

    @Bean
    RedisCacheManagerBuilderCustomizer redisCacheStatistics() {
        return builder -> builder.enableStatistics();
    }

    @Bean
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
                logFailure("read", cache, "Falling back to the database", exception);
            }

            @Override
            public void handleCachePutError(RuntimeException exception, Cache cache,
                                            Object key, Object value) {
                logFailure("write", cache, "Returning the database result", exception);
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
                logFailure("eviction", cache, "Continuing without eviction", exception);
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, Cache cache) {
                logFailure("clear", cache, "Continuing without clearing", exception);
            }
        };
    }

    private static void logFailure(String operation, Cache cache, String fallback,
                                   RuntimeException exception) {
        long now = System.nanoTime();
        long next = nextWarningNanos.get();
        if (now >= next && nextWarningNanos.compareAndSet(next, now + WARNING_INTERVAL_NANOS)) {
            log.warn("Redis cache {} failed for cache {}. {}. Cause: {}",
                    operation, cache.getName(), fallback, exception.toString());
        }
    }
}
