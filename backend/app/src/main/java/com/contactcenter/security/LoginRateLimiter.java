package com.contactcenter.security;

import com.contactcenter.domain.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Komponent ograniczający liczbę prób logowania per IP (rate limiting).
 *
 * <p>Implementacja oparta na Redis INCR + EXPIRE:
 * <ol>
 *   <li>Pierwsze wywołanie INCR tworzy klucz z wartością 1</li>
 *   <li>Dla każdego nowego klucza ustawiane jest TTL = 15 min (EXPIRE)</li>
 *   <li>Po przekroczeniu progu 5 prób rzucany jest {@link RateLimitExceededException}</li>
 *   <li>Po udanym logowaniu klucz jest usuwany przez {@link #reset(String)}</li>
 * </ol>
 *
 * <p>Klucz Redis: {@code rate:login:{ip}}, TTL: 900 s (15 min).
 *
 * <p>Atomowość: INCR jest operacją atomową w Redis – brak race condition przy
 * współbieżnych żądaniach z tego samego IP.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

    static final int MAX_ATTEMPTS = 5;
    static final long WINDOW_SECONDS = 900L; // 15 min
    private static final String KEY_PREFIX = "rate:login:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Sprawdza i inkrementuje licznik prób logowania dla danego IP.
     *
     * <p>Jeśli licznik przekroczy {@link #MAX_ATTEMPTS}, rzuca wyjątek.
     * TTL okna czasowego jest ustawiany tylko przy pierwszym wywołaniu
     * (gdy klucz nie istnieje), co zapewnia przesuwające się okno od pierwszej próby.
     *
     * @param ip adres IP klienta (z {@code HttpServletRequest.getRemoteAddr()})
     * @throws RateLimitExceededException gdy liczba prób przekroczyła limit
     */
    public void checkAndIncrement(String ip) {
        String key = KEY_PREFIX + ip;
        ValueOperations<String, String> ops = redisTemplate.opsForValue();

        Long count = ops.increment(key);

        if (count == null) {
            // Nie powinno się zdarzyć przy poprawnej konfiguracji Redis
            log.error("[RateLimit] Null count przy INCR dla klucza: {}", key);
            return;
        }

        // Ustaw TTL przy pierwszym wywołaniu (nowy klucz ma count == 1)
        if (count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(WINDOW_SECONDS));
            log.debug("[RateLimit] Nowe okno rate limit dla IP: {}", ip);
        }

        if (count > MAX_ATTEMPTS) {
            log.warn("[RateLimit] Przekroczono limit prób logowania dla IP: {} ({} prób)", ip, count);
            throw new RateLimitExceededException(
                    "Zbyt wiele prób logowania. Spróbuj ponownie za 15 minut."
            );
        }

        log.debug("[RateLimit] Próba logowania IP: {}, count: {}/{}", ip, count, MAX_ATTEMPTS);
    }

    /**
     * Usuwa licznik prób logowania po udanym zalogowaniu.
     *
     * <p>Wywołaj po pomyślnym uwierzytelnieniu, aby nie karać użytkownika
     * za poprzednie nieudane próby z tego samego IP.
     *
     * @param ip adres IP klienta
     */
    public void reset(String ip) {
        String key = KEY_PREFIX + ip;
        Boolean deleted = redisTemplate.delete(key);
        log.debug("[RateLimit] Reset licznika dla IP: {} (usunięto: {})", ip, deleted);
    }
}
