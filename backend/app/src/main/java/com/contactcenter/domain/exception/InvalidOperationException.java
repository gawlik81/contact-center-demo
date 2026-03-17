package com.contactcenter.domain.exception;

/**
 * Wyjątek domenowy rzucany gdy operacja jest niedozwolona ze względu na aktualny
 * stan zasobu (domain-level state violation).
 *
 * <p>Przykłady:
 * <ul>
 *   <li>Próba aktywacji MFA które jest już aktywne</li>
 *   <li>Próba weryfikacji TOTP bez wcześniejszego setupu</li>
 *   <li>Próba wykonania operacji na zasobie w nieprawidłowym stanie</li>
 * </ul>
 *
 * <p>Mapowany na HTTP 409 Conflict przez {@link com.contactcenter.api.GlobalExceptionHandler}.
 *
 * <p><strong>Różnica od {@link IllegalStateException}:</strong>
 * {@code InvalidOperationException} jest domenowym wyjątkiem – jego semantyka jest
 * precyzyjna i celowo mapuje na HTTP 409. {@link IllegalStateException} jest ogólnym
 * wyjątkiem JDK który może być rzucony przez wiele warstw (np. Spring, biblioteki)
 * z różnych powodów – mapowanie go globalnie na 409 było zbyt szerokie.
 */
public class InvalidOperationException extends RuntimeException {

    public InvalidOperationException(String message) {
        super(message);
    }

    public InvalidOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
