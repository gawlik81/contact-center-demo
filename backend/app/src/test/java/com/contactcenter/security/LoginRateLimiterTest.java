package com.contactcenter.security;

import com.contactcenter.domain.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link LoginRateLimiter}.
 *
 * <p>Testy weryfikują:
 * <ul>
 *   <li>Brak wyjątku przy liczbie prób poniżej limitu</li>
 *   <li>Rzucenie wyjątku po przekroczeniu MAX_ATTEMPTS (5 prób)</li>
 *   <li>Ustawianie TTL przy pierwszej próbie (nowy klucz)</li>
 *   <li>Reset licznika po udanym logowaniu</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LoginRateLimiter")
class LoginRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private LoginRateLimiter rateLimiter;

    private static final String TEST_IP = "192.168.1.100";
    private static final String EXPECTED_KEY = "rate:login:" + TEST_IP;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        rateLimiter = new LoginRateLimiter(redisTemplate);
    }

    @Nested
    @DisplayName("checkAndIncrement()")
    class CheckAndIncrement {

        @Test
        @DisplayName("pierwsza proba – nie rzuca wyjatku, ustawia TTL")
        void firstAttempt_shouldNotThrowAndSetTtl() {
            when(valueOps.increment(EXPECTED_KEY)).thenReturn(1L);

            assertThatCode(() -> rateLimiter.checkAndIncrement(TEST_IP))
                    .doesNotThrowAnyException();

            // Przy count==1 (nowy klucz) musi być ustawione TTL
            verify(redisTemplate).expire(eq(EXPECTED_KEY), eq(Duration.ofSeconds(LoginRateLimiter.WINDOW_SECONDS)));
        }

        @Test
        @DisplayName("piata proba – ostatnia dozwolona, nie rzuca wyjatku")
        void fifthAttempt_shouldNotThrow() {
            when(valueOps.increment(EXPECTED_KEY)).thenReturn(5L);

            assertThatCode(() -> rateLimiter.checkAndIncrement(TEST_IP))
                    .doesNotThrowAnyException();

            // count != 1 → TTL nie jest ponownie ustawiany
            verify(redisTemplate, never()).expire(any(), any(Duration.class));
        }

        @Test
        @DisplayName("szosta proba – przekroczenie limitu, rzuca RateLimitExceededException")
        void sixthAttempt_shouldThrowRateLimitExceededException() {
            when(valueOps.increment(EXPECTED_KEY)).thenReturn(6L);

            assertThatThrownBy(() -> rateLimiter.checkAndIncrement(TEST_IP))
                    .isInstanceOf(RateLimitExceededException.class)
                    .hasMessageContaining("Zbyt wiele prób logowania");
        }

        @Test
        @DisplayName("dwudziesta proba – nadal rzuca wyjatkiem (wysoki count)")
        void manyAttemptsOverLimit_shouldThrowRateLimitExceededException() {
            when(valueOps.increment(EXPECTED_KEY)).thenReturn(20L);

            assertThatThrownBy(() -> rateLimiter.checkAndIncrement(TEST_IP))
                    .isInstanceOf(RateLimitExceededException.class);
        }

        @Test
        @DisplayName("druga proba (count=2) – nie ustawia TTL ponownie")
        void secondAttempt_shouldNotResetTtl() {
            when(valueOps.increment(EXPECTED_KEY)).thenReturn(2L);

            assertThatCode(() -> rateLimiter.checkAndIncrement(TEST_IP))
                    .doesNotThrowAnyException();

            // TTL ustawiany tylko przy count==1
            verify(redisTemplate, never()).expire(any(), any(Duration.class));
        }

        @Test
        @DisplayName("null z Redis INCR – nie rzuca wyjatku (graceful handling)")
        void nullFromRedis_shouldNotThrow() {
            when(valueOps.increment(EXPECTED_KEY)).thenReturn(null);

            assertThatCode(() -> rateLimiter.checkAndIncrement(TEST_IP))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("reset()")
    class Reset {

        @Test
        @DisplayName("reset – wywoluje DELETE klucza")
        void reset_shouldDeleteKey() {
            when(redisTemplate.delete(EXPECTED_KEY)).thenReturn(true);

            rateLimiter.reset(TEST_IP);

            verify(redisTemplate).delete(EXPECTED_KEY);
        }

        @Test
        @DisplayName("reset dla IP bez klucza – nie rzuca wyjatku")
        void resetNonExistentKey_shouldNotThrow() {
            when(redisTemplate.delete(EXPECTED_KEY)).thenReturn(false);

            assertThatCode(() -> rateLimiter.reset(TEST_IP))
                    .doesNotThrowAnyException();
        }
    }
}
