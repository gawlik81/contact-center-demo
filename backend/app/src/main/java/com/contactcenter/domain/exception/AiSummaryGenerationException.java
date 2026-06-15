package com.contactcenter.domain.exception;

/**
 * Wyjątek rzucany gdy zewnętrzny serwis AI nie jest w stanie wygenerować podsumowania.
 *
 * <p>Obsługiwany przez {@code GlobalExceptionHandler} jako HTTP 502 Bad Gateway.
 * Oznacza błąd komunikacji lub odpowiedź błędu z zewnętrznego serwisu AI
 * (timeout, błąd HTTP, niedostępność usługi).
 *
 * <p>Przykład użycia:
 * <pre>{@code
 * throw new AiSummaryGenerationException(
 *         "Błąd HTTP " + statusCode + " od serwisu AI", cause);
 * }</pre>
 */
public class AiSummaryGenerationException extends RuntimeException {

    public AiSummaryGenerationException(String message) {
        super(message);
    }

    public AiSummaryGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
