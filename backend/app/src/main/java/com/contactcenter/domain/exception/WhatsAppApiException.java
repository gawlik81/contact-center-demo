package com.contactcenter.domain.exception;

/**
 * Wyjątek rzucany gdy wywołanie WhatsApp Business Cloud API (Meta Graph API)
 * zakończy się niepowodzeniem – błąd HTTP, timeout, błąd sieciowy.
 *
 * <p>Mapowany na HTTP 502 Bad Gateway przez {@code GlobalExceptionHandler},
 * analogicznie do {@link TwilioApiException}.
 */
public class WhatsAppApiException extends RuntimeException {

    public WhatsAppApiException(String message) {
        super(message);
    }

    public WhatsAppApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
