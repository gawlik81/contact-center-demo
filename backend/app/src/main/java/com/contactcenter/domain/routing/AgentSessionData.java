package com.contactcenter.domain.routing;

import java.util.UUID;

/**
 * Dane sesji agenta odczytane z Redis (klucz {@code session:agent:{userId}}).
 *
 * <p>Reprezentuje stan agenta w pamięci operacyjnej – status obecności
 * i przynależność do tenanta. Używany przez {@link DefaultRoutingEngine}
 * podczas skanowania dostępnych agentów.
 */
public record AgentSessionData(
        UUID agentId,
        UUID tenantId,
        String status
) {

    /** Zwraca true gdy agent jest w statusie AVAILABLE. */
    public boolean isAvailable() {
        return "AVAILABLE".equalsIgnoreCase(status);
    }

    /** Zwraca true gdy agent należy do podanego tenanta. */
    public boolean belongsToTenant(UUID tenantId) {
        return tenantId != null && tenantId.equals(this.tenantId);
    }
}
