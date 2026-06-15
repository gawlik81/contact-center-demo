package com.contactcenter.domain.tenant;

/**
 * Wynik sprawdzenia limitu zasobu tenanta.
 *
 * @param resourceType typ zasobu ("agents", "queues", "campaigns")
 * @param limit        maksymalna dozwolona liczba (z config JSONB)
 * @param current      aktualna liczba aktywnych zasobów
 */
public record LimitCheckResult(
        String resourceType,
        int limit,
        long current
) {
    /** Czy limit jest przekroczony (aktualne >= limit). */
    public boolean isExceeded() {
        return current >= limit;
    }

    /** Ile zasobów można jeszcze dodać (0 gdy przekroczony). */
    public long available() {
        return Math.max(0, limit - current);
    }
}
