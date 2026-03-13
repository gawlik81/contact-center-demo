package com.contactcenter.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Serwis zarządzający blacklistą access tokenów JWT w Redis.
 *
 * <p>Przy logout lub revoke access token jest dodawany do blacklisty na czas
 * równy pozostałemu TTL tokenu. Dzięki temu Redis automatycznie usuwa wpis
 * po wygaśnięciu tokenu (brak garbage collection po naszej stronie).
 *
 * <p>Schemat klucza Redis: {@code jwt:blacklist:{sha256_hash_tokenu}}
 * <ul>
 *   <li>Prefix: {@code jwt:blacklist:} (zgodny z konwencją projektu)</li>
 *   <li>Suffix: SHA-256 hash tokenu w hex (64 znaki) – nie przechowujemy surowego tokenu</li>
 * </ul>
 *
 * <p>Sprawdzenie blacklisty jest wykonywane w {@link JwtAuthFilter} przy każdym
 * żądaniu HTTP dla chronionych endpointów, dlatego operacja musi być O(1).
 * Redis GET ma złożoność O(1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    /** Prefix klucza Redis dla blacklisty tokenów JWT. */
    private static final String BLACKLIST_KEY_PREFIX = "jwt:blacklist:";

    /**
     * Wartość przechowywana w Redis (minimalna – tylko potwierdzenie obecności na blackliście).
     * Nie potrzebujemy wartości – samo istnienie klucza = token na blackliście.
     */
    private static final String BLACKLISTED_VALUE = "1";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Dodaje access token do blacklisty.
     *
     * <p>TTL wpisu Redis = pozostały czas ważności tokenu.
     * Po wygaśnięciu tokenu Redis automatycznie usuwa wpis.
     *
     * @param accessToken surowy JWT access token (Bearer value)
     * @param expiresAt   czas wygaśnięcia tokenu (z claim {@code exp})
     */
    public void blacklist(String accessToken, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);

        // Token już wygasł – nie trzeba blacklistować (Redis odrzuciłby TTL <= 0)
        if (ttl.isNegative() || ttl.isZero()) {
            log.debug("[TokenBlacklist] Token już wygasł, pomijam blacklistowanie");
            return;
        }

        String key = buildKey(accessToken);
        stringRedisTemplate.opsForValue().set(key, BLACKLISTED_VALUE, ttl);

        log.info("[TokenBlacklist] Token dodany do blacklisty Redis. TTL={}s", ttl.getSeconds());
    }

    /**
     * Sprawdza czy token jest na blackliście.
     *
     * @param accessToken surowy JWT access token
     * @return true gdy token jest blacklistowany (unieważniony), false gdy aktywny
     */
    public boolean isBlacklisted(String accessToken) {
        String key = buildKey(accessToken);
        Boolean exists = stringRedisTemplate.hasKey(key);
        return Boolean.TRUE.equals(exists);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Buduje klucz Redis: {@code jwt:blacklist:{sha256(token)}}.
     *
     * <p>Używamy SHA-256 zamiast surowego tokenu, żeby:
     * <ul>
     *   <li>Ograniczyć rozmiar klucza do stałych 64 znaków (hex SHA-256)</li>
     *   <li>Nie przechowywać wrażliwego tokenu jako klucza w Redis</li>
     * </ul>
     */
    private String buildKey(String accessToken) {
        String hash = sha256Hex(accessToken);
        return BLACKLIST_KEY_PREFIX + hash;
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 jest zawsze dostępny w JVM – ten wyjątek nie powinien wystąpić
            throw new IllegalStateException("SHA-256 niedostępny w JVM", e);
        }
    }
}
