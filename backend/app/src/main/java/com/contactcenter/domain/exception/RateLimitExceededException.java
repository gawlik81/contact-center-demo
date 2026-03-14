package com.contactcenter.domain.exception;

/**
 * Rzucany gdy klient przekroczy dopuszczalną liczbę prób logowania w oknie czasowym.
 *
 * <p>Obsługiwany przez {@code GlobalExceptionHandler} → HTTP 429 Too Many Requests
 * z nagłówkiem {@code Retry-After: 900} (15 minut).
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
