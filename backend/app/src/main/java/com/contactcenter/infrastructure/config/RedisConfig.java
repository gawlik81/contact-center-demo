package com.contactcenter.infrastructure.config;

import com.contactcenter.api.admin.dto.AdminMetricsResponse;
import com.contactcenter.api.admin.dto.ContactChannelMatrix;
import com.contactcenter.api.admin.dto.GrowthMetrics;
import com.contactcenter.api.admin.dto.UsageMetrics;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
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
    /** TTL sesji połączeń Twilio: 24h pokrywa najdłuższe połączenie + 2 min bufor na callbacki. */
    public static final Duration TTL_CALL_SESSION     = Duration.ofHours(24);
    /**
     * TTL metryk wzrostu platformy (nowi tenanci/użytkownicy tygodniowo, top pluginy).
     * Dłuższy niż pozostałe cache metryk admina – dane zmieniają się wolno (agregacje tygodniowe).
     */
    public static final Duration TTL_ADMIN_METRICS_GROWTH = Duration.ofMinutes(20);

    /**
     * StringRedisTemplate – klucze i wartości serializowane jako String (UTF-8).
     *
     * <p>Używany przez serwisy operujące na prostych wartościach tekstowych:
     * MfaService (TOTP replay prevention), TokenBlacklistService (JWT blacklista),
     * LoginRateLimiter (INCR/EXPIRE counters).
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

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

        // Serializator typowany dla AdminMetricsResponse.
        //
        // Problem: Java record kompiluje się do final class. GenericJackson2JsonRedisSerializer
        // z DefaultTyping.NON_FINAL pomija typy final przy generowaniu pola @class w JSON.
        // Przy deserializacji do Object Jackson nie ma type metadata i rzuca
        // "missing type id property '@class'".
        //
        // Rozwiązanie: Jackson2JsonRedisSerializer z jawnie podanym typem docelowym.
        // Nie wymaga @class w JSON – deserializuje zawsze do AdminMetricsResponse.
        // Obiekt ObjectMapper bez defaultTyping jest wystarczający (typ znany statycznie).
        ObjectMapper adminMetricsObjectMapper = new ObjectMapper();
        adminMetricsObjectMapper.registerModule(new JavaTimeModule());
        adminMetricsObjectMapper.disable(
                com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        Jackson2JsonRedisSerializer<AdminMetricsResponse> adminMetricsSerializer =
                new Jackson2JsonRedisSerializer<>(adminMetricsObjectMapper, AdminMetricsResponse.class);

        RedisCacheConfiguration adminMetricsConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(TTL_ADMIN_METRICS)
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                adminMetricsSerializer))
                .disableCachingNullValues();

        // Serializatory typowane dla UsageMetrics/GrowthMetrics – ten sam problem @class co przy
        // AdminMetricsResponse (record = final class, patrz Javadoc poniżej i wyżej).
        Jackson2JsonRedisSerializer<UsageMetrics> usageMetricsSerializer =
                new Jackson2JsonRedisSerializer<>(adminMetricsObjectMapper, UsageMetrics.class);
        RedisCacheConfiguration usageMetricsConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(TTL_REPORT_CACHE)
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                usageMetricsSerializer))
                .disableCachingNullValues();

        Jackson2JsonRedisSerializer<GrowthMetrics> growthMetricsSerializer =
                new Jackson2JsonRedisSerializer<>(adminMetricsObjectMapper, GrowthMetrics.class);
        RedisCacheConfiguration growthMetricsConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(TTL_ADMIN_METRICS_GROWTH)
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                growthMetricsSerializer))
                .disableCachingNullValues();

        // Serializator typowany dla ContactChannelMatrix – OSOBNY cache name od
        // ADMIN_METRICS_USAGE/UsageMetrics. Współdzielenie jednego cache name przez dwa różne
        // typy DTO (z różnymi kluczami "usage"/"channel-breakdown") powodowało
        // UnrecognizedPropertyException przy odczycie – Jackson2JsonRedisSerializer jest
        // typowany na konkretną klasę per cache name, nie per klucz w tym cache.
        Jackson2JsonRedisSerializer<ContactChannelMatrix> channelMatrixSerializer =
                new Jackson2JsonRedisSerializer<>(adminMetricsObjectMapper, ContactChannelMatrix.class);
        RedisCacheConfiguration channelMatrixConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(TTL_REPORT_CACHE)
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                channelMatrixSerializer))
                .disableCachingNullValues();

        // Konfiguracje per cache z właściwymi TTL
        Map<String, RedisCacheConfiguration> cacheConfigurations = Map.of(
                CacheNames.QUEUE_STATS,    defaultConfig.entryTtl(TTL_QUEUE_STATS),
                CacheNames.ADMIN_METRICS,  adminMetricsConfig,
                CacheNames.ADMIN_METRICS_USAGE,  usageMetricsConfig,
                CacheNames.ADMIN_METRICS_GROWTH, growthMetricsConfig,
                CacheNames.ADMIN_METRICS_CHANNEL_BREAKDOWN, channelMatrixConfig,
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
     *
     * <p><strong>Bezpieczeństwo deserializacji:</strong> Poprzednia implementacja używała
     * {@code LaissezFaireSubTypeValidator.instance} – nie weryfikuje typów podczas
     * deserializacji JSON z Redis, co otwiera wektor ataku przez gadget chains (RCE)
     * gdy atakujący może zapisywać do Redis.
     *
     * <p>Zastąpiono przez {@link BasicPolymorphicTypeValidator} z białą listą pakietów:
     * {@code com.contactcenter} (klasy aplikacji) i {@code java.util} (standardowe kolekcje).
     * Próba deserializacji do klasy spoza białej listy rzuci {@code InvalidTypeIdException}.
     */
    private ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());

        // Biała lista typów dozwolonych przy deserializacji z Redis
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType("com.contactcenter")
                .allowIfSubType("com.contactcenter")
                .allowIfSubType("java.util")
                .build();

        // Zapisuje informację o typie Java – umożliwia deserializację polimorficznych obiektów
        mapper.activateDefaultTyping(
                ptv,
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
        /** Cache dla {@code GET /api/admin/metrics/usage} (TTL 5 min, jak {@link #REPORT_CACHE}). */
        public static final String ADMIN_METRICS_USAGE  = "admin-metrics-usage";
        /** Cache dla {@code GET /api/admin/metrics/growth} (TTL 20 min – dane wolno się zmieniają). */
        public static final String ADMIN_METRICS_GROWTH = "admin-metrics-growth";
        /**
         * Cache dla {@code ContactChannelMatrix} (macierz kontaktów per tenant/kanał, TTL 5 min,
         * jak {@link #REPORT_CACHE}). OSOBNY od {@link #ADMIN_METRICS_USAGE} – każdy cache name
         * ma dedykowany typowany serializer (patrz {@code redisCacheManager}); dwa różne typy DTO
         * pod jednym cache name powodują {@code UnrecognizedPropertyException} przy odczycie.
         */
        public static final String ADMIN_METRICS_CHANNEL_BREAKDOWN = "admin-metrics-channel-breakdown";
        public static final String CLI_LOOKUP     = "cli-lookup";
        public static final String REPORT_CACHE   = "report-cache";
        public static final String TTS_AUDIO      = "tts-audio";
    }
}
