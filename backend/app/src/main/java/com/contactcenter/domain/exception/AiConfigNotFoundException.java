package com.contactcenter.domain.exception;

/**
 * Wyjątek rzucany gdy tenant nie ma skonfigurowanego dostawcy AI.
 *
 * <p>Obsługiwany przez {@code GlobalExceptionHandler} jako HTTP 422 Unprocessable Entity.
 * Oznacza, że żądana operacja AI jest semantycznie poprawna, ale nie może być przetworzona
 * ponieważ brakuje konfiguracji (klucz API, wybór dostawcy) w ustawieniach tenanta.
 *
 * <p>Przykład użycia:
 * <pre>{@code
 * aiConfigService.getDecryptedConfig(tenantId)
 *         .orElseThrow(() -> new AiConfigNotFoundException(
 *                 "Brak konfiguracji AI dla tenanta. Skonfiguruj dostawcę AI w ustawieniach."));
 * }</pre>
 */
public class AiConfigNotFoundException extends RuntimeException {

    public AiConfigNotFoundException(String message) {
        super(message);
    }
}
