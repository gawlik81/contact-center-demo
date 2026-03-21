package com.contactcenter.domain.routing;

import java.util.UUID;

/**
 * Wynik routingu – agent wybrany przez {@link RoutingEngine}.
 *
 * @param agentId   UUID agenta wybranego do obsługi kontaktu
 * @param strategy  nazwa strategii która znalazła agenta (np. "STICKY", "SKILL_BASED")
 * @param isSticky  true gdy wynik pochodzi z mechanizmu sticky agent
 */
public record RoutingResult(
        UUID agentId,
        String strategy,
        boolean isSticky
) {

    /** Fabryka dla wyniku sticky agent. */
    public static RoutingResult sticky(UUID agentId) {
        return new RoutingResult(agentId, "STICKY", true);
    }

    /** Fabryka dla wyniku standardowej strategii. */
    public static RoutingResult of(UUID agentId, String strategy) {
        return new RoutingResult(agentId, strategy, false);
    }
}
