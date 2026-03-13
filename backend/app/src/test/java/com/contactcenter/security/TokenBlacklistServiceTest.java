package com.contactcenter.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link TokenBlacklistService}.
 *
 * <p>Testy weryfikują:
 * <ul>
 *   <li>Dodawanie tokenu do blacklisty z właściwym TTL</li>
 *   <li>Sprawdzanie czy token jest na blackliście</li>
 *   <li>Pomijanie już wygasłych tokenów</li>
 *   <li>Format klucza Redis (prefix + sha256 hash)</li>
 * </ul>
 *
 * <p>LENIENT: stubbing {@code opsForValue()} w setUp jest potrzebny tylko dla
 * testów grupy Blacklist; testy IsBlacklisted używają {@code hasKey()}.
 * MockitoSettings.LENIENT zapobiega UnnecessaryStubbingException.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TokenBlacklistService – blacklista JWT w Redis")
class TokenBlacklistServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private TokenBlacklistService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new TokenBlacklistService(redisTemplate);
    }

    // =========================================================================
    // blacklist()
    // =========================================================================

    @Nested
    @DisplayName("blacklist()")
    class Blacklist {

        @Test
        @DisplayName("Wywołuje Redis SET z TTL dla aktywnego tokenu")
        void shouldCallRedisSetWithTtl() {
            String token = "sample.jwt.token";
            Instant expiresAt = Instant.now().plusSeconds(600); // 10 min

            service.blacklist(token, expiresAt);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);

            verify(valueOps).set(keyCaptor.capture(), eq("1"), ttlCaptor.capture());

            String key = keyCaptor.getValue();
            assertThat(key).startsWith("jwt:blacklist:");
            assertThat(key).hasSize("jwt:blacklist:".length() + 64); // SHA-256 = 64 hex chars

            Duration ttl = ttlCaptor.getValue();
            assertThat(ttl.getSeconds()).isGreaterThan(500).isLessThanOrEqualTo(600);
        }

        @Test
        @DisplayName("Pomija już wygasłe tokeny (TTL <= 0)")
        void shouldSkipExpiredToken() {
            String token = "expired.jwt.token";
            Instant expiresAt = Instant.now().minusSeconds(60); // wygasł minutę temu

            service.blacklist(token, expiresAt);

            verifyNoInteractions(valueOps);
        }

        @Test
        @DisplayName("Klucz Redis jest deterministyczny (ten sam token = ten sam klucz)")
        void keyIsDeterministic() {
            String token = "same.token.value";
            Instant expiresAt = Instant.now().plusSeconds(300);

            service.blacklist(token, expiresAt);
            service.blacklist(token, expiresAt);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(valueOps, times(2)).set(keyCaptor.capture(), eq("1"), any());

            assertThat(keyCaptor.getAllValues().get(0))
                    .isEqualTo(keyCaptor.getAllValues().get(1));
        }

        @Test
        @DisplayName("Różne tokeny mają różne klucze Redis")
        void differentTokensHaveDifferentKeys() {
            String token1 = "token.one";
            String token2 = "token.two";
            Instant expiresAt = Instant.now().plusSeconds(300);

            service.blacklist(token1, expiresAt);
            service.blacklist(token2, expiresAt);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(valueOps, times(2)).set(keyCaptor.capture(), eq("1"), any());

            assertThat(keyCaptor.getAllValues().get(0))
                    .isNotEqualTo(keyCaptor.getAllValues().get(1));
        }
    }

    // =========================================================================
    // isBlacklisted()
    // =========================================================================

    @Nested
    @DisplayName("isBlacklisted()")
    class IsBlacklisted {

        @Test
        @DisplayName("Zwraca true gdy klucz istnieje w Redis")
        void shouldReturnTrueWhenKeyExists() {
            when(redisTemplate.hasKey(anyString())).thenReturn(Boolean.TRUE);

            assertThat(service.isBlacklisted("any.token")).isTrue();
        }

        @Test
        @DisplayName("Zwraca false gdy klucz nie istnieje w Redis")
        void shouldReturnFalseWhenKeyAbsent() {
            when(redisTemplate.hasKey(anyString())).thenReturn(Boolean.FALSE);

            assertThat(service.isBlacklisted("any.token")).isFalse();
        }

        @Test
        @DisplayName("Zwraca false gdy Redis zwraca null (timeout)")
        void shouldReturnFalseWhenRedisReturnsNull() {
            when(redisTemplate.hasKey(anyString())).thenReturn(null);

            assertThat(service.isBlacklisted("any.token")).isFalse();
        }

        @Test
        @DisplayName("Klucz sprawdzany w Redis ma prawidłowy format")
        void shouldQueryCorrectRedisKey() {
            when(redisTemplate.hasKey(anyString())).thenReturn(Boolean.FALSE);

            service.isBlacklisted("test.token");

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(redisTemplate).hasKey(keyCaptor.capture());

            String key = keyCaptor.getValue();
            assertThat(key).startsWith("jwt:blacklist:");
            // SHA-256 hash = 64 znaki hex
            assertThat(key.substring("jwt:blacklist:".length())).hasSize(64);
            assertThat(key.substring("jwt:blacklist:".length())).matches("[0-9a-f]{64}");
        }
    }
}
