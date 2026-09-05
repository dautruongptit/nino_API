package com.app.nino.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

@Slf4j
@Configuration
@EnableCaching
@RequiredArgsConstructor
public class RedisConfig implements CachingConfigurer {

    private final RedisConnectionFactory connectionFactory;

    // ── Serializer ────────────────────────────────────────────────────────────
    private GenericJackson2JsonRedisSerializer jacksonSerializer() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.activateDefaultTyping(
            LaissezFaireSubTypeValidator.instance,
            ObjectMapper.DefaultTyping.NON_FINAL,
            JsonTypeInfo.As.PROPERTY
        );
        return new GenericJackson2JsonRedisSerializer(om);
    }

    // ── RedisTemplate ─────────────────────────────────────────────────────────
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> tpl = new RedisTemplate<>();
        tpl.setConnectionFactory(factory);
        tpl.setKeySerializer(new StringRedisSerializer());
        tpl.setHashKeySerializer(new StringRedisSerializer());
        tpl.setValueSerializer(jacksonSerializer());
        tpl.setHashValueSerializer(jacksonSerializer());
        tpl.afterPropertiesSet();
        return tpl;
    }

    // ── CacheManager ──────────────────────────────────────────────────────────
    @Bean
    @Override
    public CacheManager cacheManager() {
        RedisCacheConfiguration defaultCfg = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .serializeKeysWith(
                RedisSerializationContext.SerializationPair
                    .fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(
                RedisSerializationContext.SerializationPair
                    .fromSerializer(jacksonSerializer()))
            .disableCachingNullValues();

        // TTL riêng theo từng cache region
        Map<String, RedisCacheConfiguration> cacheConfigs = Map.of(
            "home",           defaultCfg.entryTtl(Duration.ofMinutes(5)),
            "upcoming",       defaultCfg.entryTtl(Duration.ofMinutes(5)),
            "relatives",      defaultCfg.entryTtl(Duration.ofMinutes(10)),
            "relativeDetail", defaultCfg.entryTtl(Duration.ofMinutes(10)),
            "events",         defaultCfg.entryTtl(Duration.ofMinutes(10)),
            "unreadCount",    defaultCfg.entryTtl(Duration.ofMinutes(2)),
            "userProfile",    defaultCfg.entryTtl(Duration.ofMinutes(30))
        );

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(defaultCfg)
            .withInitialCacheConfigurations(cacheConfigs)
            .build();
    }

    // ── CacheErrorHandler — Graceful fallback khi Redis DOWN ─────────────────
    @Bean
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e,
                    org.springframework.cache.Cache cache, Object key) {
                log.warn("[Cache] GET loi key={}, fallback ve DB: {}", key, e.getMessage());
            }
            @Override
            public void handleCachePutError(RuntimeException e,
                    org.springframework.cache.Cache cache, Object key, Object value) {
                log.warn("[Cache] PUT loi key={}: {}", key, e.getMessage());
            }
            @Override
            public void handleCacheEvictError(RuntimeException e,
                    org.springframework.cache.Cache cache, Object key) {
                log.warn("[Cache] EVICT loi key={}: {}", key, e.getMessage());
            }
            @Override
            public void handleCacheClearError(RuntimeException e,
                    org.springframework.cache.Cache cache) {
                log.warn("[Cache] CLEAR loi: {}", e.getMessage());
            }
        };
    }
}
