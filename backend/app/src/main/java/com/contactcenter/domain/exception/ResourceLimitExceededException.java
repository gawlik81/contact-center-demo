package com.contactcenter.domain.exception;

/**
 * Wyjątek rzucany gdy próba dodania zasobu (agent/kolejka/kampania) przekroczyłaby
 * limit skonfigurowany w JSONB polu {@code config} tabeli {@code tenant}.
 *
 * <p>Obsługiwany przez {@code GlobalExceptionHandler} jako HTTP 422 Unprocessable Entity
 * z body zawierającym szczegóły o przekroczeniu limitu:
 * <pre>
 * {
 *   "error": "RESOURCE_LIMIT_EXCEEDED",
 *   "message": "Przekroczono limit agentów (limit: 100, aktualne: 100)",
 *   "resourceType": "agents",
 *   "limit": 100,
 *   "current": 100
 * }
 * </pre>
 *
 * <p>Rzucany przez {@code TenantResourceLimitService} i reużywany przez moduły:
 * BE-008 (zarządzanie agentami), BE-020 (kolejki), BE-022 (kampanie).
 */
public class ResourceLimitExceededException extends RuntimeException {

    private final String resourceType;
    private final int limit;
    private final long current;

    /**
     * Tworzy wyjątek z informacją o typie zasobu, limicie i aktualnej liczbie.
     *
     * @param resourceType typ zasobu: "agents", "queues", "campaigns"
     * @param limit        maksymalna dozwolona liczba zasobów (z config JSONB tenanta)
     * @param current      aktualna liczba zasobów tenanta
     */
    public ResourceLimitExceededException(String resourceType, int limit, long current) {
        super(String.format(
                "Przekroczono limit zasobu '%s' (limit: %d, aktualne: %d). " +
                "Skontaktuj się z administratorem aby zwiększyć limit.",
                resourceType, limit, current
        ));
        this.resourceType = resourceType;
        this.limit = limit;
        this.current = current;
    }

    public String getResourceType() {
        return resourceType;
    }

    public int getLimit() {
        return limit;
    }

    public long getCurrent() {
        return current;
    }
}
