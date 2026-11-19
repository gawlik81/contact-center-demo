package com.contactcenter.security;

import org.springframework.messaging.MessagingException;

/**
 * Oczekiwane odrzucenie ramki STOMP przez interceptory kanału wejściowego (uwierzytelnienie CONNECT w
 * {@link WebSocketAuthInterceptor}, autoryzacja SUBSCRIBE/SEND w {@link StompDestinationAuthorizationInterceptor}).
 * Niesie {@link WsErrorCode}, który {@link StompErrorCodeHandler} wystawia klientowi w nagłówku {@code code}
 * ramki ERROR (BE-236). Rozszerza {@link MessagingException}, więc dotychczasowa obsługa (ERROR + zamknięcie
 * sesji, nagłówek {@code message}) pozostaje bez zmian.
 */
public class StompRejectedException extends MessagingException {

    private final WsErrorCode code;

    public StompRejectedException(WsErrorCode code, String description) {
        super(description);
        this.code = code;
    }

    public StompRejectedException(WsErrorCode code, String description, Throwable cause) {
        super(description, cause);
        this.code = code;
    }

    public WsErrorCode getCode() {
        return code;
    }
}
