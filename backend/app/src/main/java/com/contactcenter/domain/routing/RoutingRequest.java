package com.contactcenter.domain.routing;

import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.model.Queue;
import com.contactcenter.domain.service.RoutingService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Żądanie routingu kontaktu do agenta.
 *
 * <p>Zawiera wszystkie dane potrzebne {@link RoutingEngine} do wyznaczenia
 * najlepszego agenta dla danego kontaktu. Budowany przez {@link RoutingService}
 * na podstawie konfiguracji kolejki.
 *
 * @param tenantId          UUID tenanta – izolacja multi-tenant
 * @param queueId           UUID kolejki źródłowej
 * @param routingStrategy   strategia routingu: ROUND_ROBIN, FIRST_AVAILABLE, SKILL_BASED
 * @param requiredSkills    lista wymaganych skills agenta (pusta = brak wymagań)
 * @param preferredAgentId  UUID preferowanego agenta (sticky agent) lub null
 * @param contactChannel    kanał kontaktu: VOICE, EMAIL, CHAT
 */
public record RoutingRequest(
        UUID tenantId,
        UUID queueId,
        String routingStrategy,
        List<String> requiredSkills,
        UUID preferredAgentId,
        String contactChannel
) {

    /** Zwraca true gdy skonfigurowany jest sticky agent. */
    public boolean hasStickyAgent() {
        return preferredAgentId != null;
    }

    /** Zwraca true gdy strategia wymaga dopasowania skills. */
    public boolean requiresSkillMatch() {
        return requiredSkills != null && !requiredSkills.isEmpty();
    }

    /**
     * Buduje {@link RoutingRequest} na podstawie encji kontaktu i kolejki.
     *
     * <p>Sticky agent: pobierany z {@code contact.agentId} (np. ustawiony przez IVR
     * lub poprzedni kontakt klienta w tej sesji).
     *
     * @param contact  encja kontaktu
     * @param queue    encja kolejki
     * @param tenantId UUID tenanta
     * @return żądanie routingu gotowe do przekazania do {@link RoutingEngine}
     */
    public static RoutingRequest of(Contact contact, Queue queue, UUID tenantId) {
        return new RoutingRequest(
                tenantId,
                queue.getQueueId(),
                queue.getRoutingStrategy() != null ? queue.getRoutingStrategy() : "FIRST_AVAILABLE",
                queue.getRequiredSkills() != null ? new ArrayList<>(queue.getRequiredSkills()) : List.of(),
                contact.getAgentId(),   // sticky agent (może być null)
                contact.getChannel()
        );
    }
}
