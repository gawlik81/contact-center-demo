package com.contactcenter.domain.routing;

import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.model.Queue;
import com.contactcenter.domain.service.RoutingService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * @param eligibleAgentIds  zbiór UUID agentów uprawnionych do obsługi kolejki;
 *                          {@code null} oznacza all_agents=TRUE (brak filtru – wszyscy agenci tenanta)
 */
public record RoutingRequest(
        UUID tenantId,
        UUID queueId,
        String routingStrategy,
        List<String> requiredSkills,
        UUID preferredAgentId,
        String contactChannel,
        Set<UUID> eligibleAgentIds
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
     * Zwraca true gdy kolejka ma all_agents=FALSE i routing musi filtrować po liście agentów.
     * Gdy false (eligibleAgentIds==null) – all_agents=TRUE, wszyscy agenci tenanta są kandydatami.
     */
    public boolean hasAgentFilter() {
        return eligibleAgentIds != null;
    }

    /**
     * Buduje {@link RoutingRequest} na podstawie encji kontaktu i kolejki.
     *
     * <p>Sticky agent: pobierany z {@code contact.agentId} (np. ustawiony przez IVR
     * lub poprzedni kontakt klienta w tej sesji).
     *
     * <p>Gdy {@code eligibleAgentIds == null} oznacza all_agents=TRUE – silnik routingu
     * nie stosuje filtru i bierze pod uwagę wszystkich dostępnych agentów tenanta.
     *
     * @param contact          encja kontaktu
     * @param queue            encja kolejki
     * @param tenantId         UUID tenanta
     * @param eligibleAgentIds zbiór uprawnionych agentów lub null gdy all_agents=TRUE
     * @return żądanie routingu gotowe do przekazania do {@link RoutingEngine}
     */
    public static RoutingRequest of(Contact contact, Queue queue, UUID tenantId,
                                    Set<UUID> eligibleAgentIds) {
        return new RoutingRequest(
                tenantId,
                queue.getQueueId(),
                queue.getRoutingStrategy() != null ? queue.getRoutingStrategy() : "FIRST_AVAILABLE",
                queue.getRequiredSkills() != null ? new ArrayList<>(queue.getRequiredSkills()) : List.of(),
                contact.getAgentId(),   // sticky agent (może być null)
                contact.getChannel(),
                eligibleAgentIds
        );
    }
}
