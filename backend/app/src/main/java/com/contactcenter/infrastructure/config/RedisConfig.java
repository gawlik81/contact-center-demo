package com.contactcenter.infrastructure.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.EnableCaching;
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

/**
 * Konfiguracja Redis – cache, JWT blacklista, stan agentów.
 *
 * <p>Klucze Redis (konwencja): {@code {namespace}:{tenantId}:{entityType}:{id}}
 * Szczegółowa dokumentacja kluczy: {@code backend/src/main/resources/redis-keys.md}.
 *
 * <p>Użycie Redis w projekcie:
 * <ul>
 *   <li>JWT blacklista (logout/revoke) – TTL = pozostały czas ważności tokenu</li>
 *   <li>Agent presence – status agentów (TTL = 30s, odnawiane przy heartbeat)</li>
 *   <li>Cache kolejek (stats) – TTL 5s</li>
 *   <li>Cache metryk admin – TTL 30s</li>
 *   <li>Cache CLI lookup (numery telefonów) – TTL 5 min</li>
 *   <li>Cache TTS (audio files) – TTL 24h</li>
 *   <li>Rate limiting (logowanie) – TTL 15 min</li>
 *   <li>Sesje voicebot – TTL 15 min</li>
 * </ul>
 */
@Slf4j
@Configuration
@EnableCaching
public class RedisConfig {

    // TTL per cache – centralnie zarządzane
    public static final Duration TTL_JWT_BLACKLIST    = Duration.ofMinutes(16);  // > access token TTL 15 min
    public static final Duration TTL_AGENT_PRESENCE   = Duration.ofSeconds(30);
    public static final Duration TTL_QUEUE_STATS      = Duration.ofSeconds(5);
    public static final Duration TTL_ADMIN_METRICS    = Duration.ofSeconds(30);
    public static final Duration TTL_CLI_LOOKUP       = Duration.ofMinutes(5);
    public static final Duration TTL_TTS_AUDIO        = Duration.ofHours(24);
    public static final Duration TTL_RATE_LIMIT       = Duration.ofMinutes(15);
    public static final Duration TTL_VOICEBOT_SESSION = Duration.ofMinutes(15);
    public static final Duration TTL_REPORT_CACHE     = Duration.ofMinutes(5);

    /**
     * RedisTemplate z serializacją String klucz / JSON wartość.
     *
     * <p>Używany bezpośrednio w serwisach (JWT blacklista, rate limiting,
     * agent presence – tam gdzie potrzebujemy operacji atomowych EXPIRE, INCR, SETNX).
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Klucze jako String (czytelne w Redis CLI)
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Wartości jako JSON (z informacją o typie – umożliwia deserializację)
        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(redisObjectMapper());
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();

        log.info("[Redis] RedisTemplate skonfigurowany (String keys, JSON values)");
        return template;
    }

    /**
     * RedisCacheManager – używany przez @Cacheable/@CacheEvict/@CachePut.
     *
     * <p>Konfiguracja per cache name z właściwymi TTL.
     * Klucze cache: automatycznie prefiksowane przez Spring.
     */
    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        // Domyślna konfiguracja (fallback dla nieznanych cache names)
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer(redisObjectMapper())))
                // Nie cachujemy wartości null (zapobiega cache poisoning)
                .disableCachingNullValues();

        // Konfiguracje per cache z właściwymi TTL
        Map<String, RedisCacheConfiguration> cacheConfigurations = Map.of(
                CacheNames.QUEUE_STATS,    defaultConfig.entryTtl(TTL_QUEUE_STATS),
                CacheNames.ADMIN_METRICS,  defaultConfig.entryTtl(TTL_ADMIN_METRICS),
                CacheNames.CLI_LOOKUP,     defaultConfig.entryTtl(TTL_CLI_LOOKUP),
                CacheNames.REPORT_CACHE,   defaultConfig.entryTtl(TTL_REPORT_CACHE),
                CacheNames.TTS_AUDIO,      defaultConfig.entryTtl(TTL_TTS_AUDIO)
        );

        log.info("[Redis] RedisCacheManager skonfigurowany. Zdefiniowane cache: {}",
                cacheConfigurations.keySet());

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }

    /**
     * ObjectMapper dla Redis – z obsługą Java 8 Date/Time i informacji o typie.
     */
    private ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // Zapisuje informację o typie Java – umożliwia deserializację polimorficznych obiektów
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY
        );
        return mapper;
    }

    /**
     * Stałe nazw cache – używane w @Cacheable(cacheNames = CacheNames.XXX).
     *
     * <p>Centralnie zdefiniowane, żeby uniknąć literówek w adnotacjach.
     */
    public static final class CacheNames {
        private CacheNames() { }

        public static final String QUEUE_STATS    = "queue-stats";
        public static final String ADMIN_METRICS  = "admin-metrics";
        public static final String CLI_LOOKUP     = "cli-lookup";
        public static final String REPORT_CACHE   = "report-cache";
        public static final String TTS_AUDIO      = "tts-audio";
    }
}
