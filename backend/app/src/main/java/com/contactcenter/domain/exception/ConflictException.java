package com.contactcenter.domain.exception;

/**
 * Wyjątek rzucany gdy operacja jest niemożliwa ze względu na aktualny stan zasobu.
 *
 * <p>Mapowany na HTTP 409 Conflict przez {@code GlobalExceptionHandler}.
 *
 * <p>Przykłady użycia:
 * <ul>
 *   <li>Próba usunięcia agenta z aktywnymi kontaktami (BE-008)</li>
 *   <li>Próba dezaktywacji kolejki z kontaktami w toku</li>
 * </ul>
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }

    public ConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
