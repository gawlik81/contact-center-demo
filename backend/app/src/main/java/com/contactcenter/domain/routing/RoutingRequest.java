package com.contactcenter.domain.routing;

import com.contactcenter.domain.contact.Contact;
import com.contactcenter.domain.contact.ContactChannels;
import com.contactcenter.domain.queue.Queue;

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
 * @param excludedAgentIds  agenci wykluczeni dla TEGO kontaktu (anti-ping-pong po zwrocie do kolejki,
 *                          BE-160/BE-156); pusty zbiór = brak wykluczeń. Dotyczy wszystkich strategii i sticky
 * @param maxConcurrentAsyncPerAgent limit otwartych kontaktów ASYNC (EMAIL/CHAT/SOCIAL) agenta w tej kolejce
 *                          (BE-156, {@code queue.max_concurrent_contacts_per_agent}); {@code null} = bez limitu
 *                          (kontakt telefoniczny - telefon sterowany statusem BUSY, nie tym limitem)
 * @param retryRound        true gdy to PONOWNE pytanie silnika w tym samym przebiegu (poprzednio wybrany agent
 *                          okazał się pełny przy zapisie); ROUND_ROBIN nie przesuwa wtedy licznika rotacji
 */
public record RoutingRequest(
        UUID tenantId,
        UUID queueId,
        String routingStrategy,
        List<String> requiredSkills,
        UUID preferredAgentId,
        String contactChannel,
        Set<UUID> eligibleAgentIds,
        Set<UUID> excludedAgentIds,
        Integer maxConcurrentAsyncPerAgent,
        boolean retryRound
) {

    /** Compact constructor: a missing exclusion set means "no exclusions". */
    public RoutingRequest {
        excludedAgentIds = excludedAgentIds != null ? excludedAgentIds : Set.of();
    }

    /** Request without an async load limit (phone path, legacy callers). */
    public RoutingRequest(UUID tenantId, UUID queueId, String routingStrategy, List<String> requiredSkills,
                          UUID preferredAgentId, String contactChannel, Set<UUID> eligibleAgentIds,
                          Set<UUID> excludedAgentIds) {
        this(tenantId, queueId, routingStrategy, requiredSkills, preferredAgentId, contactChannel,
                eligibleAgentIds, excludedAgentIds, null, false);
    }

    /** Request with an async limit, first round (legacy 9-argument form). */
    public RoutingRequest(UUID tenantId, UUID queueId, String routingStrategy, List<String> requiredSkills,
                          UUID preferredAgentId, String contactChannel, Set<UUID> eligibleAgentIds,
                          Set<UUID> excludedAgentIds, Integer maxConcurrentAsyncPerAgent) {
        this(tenantId, queueId, routingStrategy, requiredSkills, preferredAgentId, contactChannel,
                eligibleAgentIds, excludedAgentIds, maxConcurrentAsyncPerAgent, false);
    }

    /** Same request with a larger exclusion set (agents rejected at write time because they are at the limit). */
    public RoutingRequest withExcludedAgents(Set<UUID> newExcludedAgentIds) {
        return new RoutingRequest(tenantId, queueId, routingStrategy, requiredSkills, preferredAgentId,
                contactChannel, eligibleAgentIds, newExcludedAgentIds, maxConcurrentAsyncPerAgent, true);
    }

    /** True when agents holding {@code maxConcurrentAsyncPerAgent} open async contacts in this queue are skipped. */
    public boolean hasAsyncLimit() {
        return maxConcurrentAsyncPerAgent != null;
    }

    /** Request without per-contact exclusions (phone path, legacy callers). */
    public RoutingRequest(UUID tenantId, UUID queueId, String routingStrategy, List<String> requiredSkills,
                          UUID preferredAgentId, String contactChannel, Set<UUID> eligibleAgentIds) {
        this(tenantId, queueId, routingStrategy, requiredSkills, preferredAgentId, contactChannel,
                eligibleAgentIds, Set.of());
    }

    /** True when the agent must not receive this contact (it was just returned to the queue by them). */
    public boolean isExcluded(UUID agentId) {
        return excludedAgentIds.contains(agentId);
    }

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
        return of(contact, queue, tenantId, eligibleAgentIds, Set.of());
    }

    /** Same as above with the agents excluded for this contact (BE-160 anti-ping-pong). */
    public static RoutingRequest of(Contact contact, Queue queue, UUID tenantId,
                                    Set<UUID> eligibleAgentIds, Set<UUID> excludedAgentIds) {
        return new RoutingRequest(
                tenantId,
                queue.getQueueId(),
                queue.getRoutingStrategy() != null ? queue.getRoutingStrategy() : "FIRST_AVAILABLE",
                queue.getRequiredSkills() != null ? new ArrayList<>(queue.getRequiredSkills()) : List.of(),
                contact.getAgentId(),   // sticky agent (może być null)
                contact.getChannel(),
                eligibleAgentIds,
                excludedAgentIds,
                ContactChannels.isAsync(contact.getChannel()) ? queue.getMaxConcurrentContactsPerAgent() : null
        );
    }
}
