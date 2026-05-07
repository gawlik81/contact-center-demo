package com.contactcenter.domain.exception;

/**
 * Wyjątek rzucany gdy Twilio API zwróci błąd lub jest niedostępne (HTTP 502).
 *
 * <p>Obsługiwany przez {@code GlobalExceptionHandler} jako HTTP 502 Bad Gateway.
 * Używaj gdy wywołanie zewnętrznego API Twilio zakończy się niepowodzeniem
 * i błąd nie leży po stronie klienta (np. nieprawidłowe credencjały, API timeout).
 */
public class TwilioApiException extends RuntimeException {

    public TwilioApiException(String message) {
        super(message);
    }

    public TwilioApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
