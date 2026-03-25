package com.contactcenter.domain.exception;

/**
 * Wyjątek rzucany gdy żądany zasób nie istnieje (HTTP 404).
 *
 * <p>Obsługiwany przez {@code GlobalExceptionHandler} jako HTTP 404 Not Found.
 * Używaj zamiast {@code jakarta.persistence.EntityNotFoundException} gdy chcesz
 * jednoznacznie mapować na HTTP 404 w API.
 *
 * <p>Przykład użycia:
 * <pre>{@code
 * return ivrRepository.findById(id)
 *         .orElseThrow(() -> new ResourceNotFoundException("IVR nie istnieje: " + id));
 * }</pre>
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
