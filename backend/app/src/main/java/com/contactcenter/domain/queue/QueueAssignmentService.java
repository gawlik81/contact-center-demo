package com.contactcenter.domain.queue;

import com.contactcenter.api.queue.dto.QueueAssignmentResponse;
import com.contactcenter.api.queue.dto.UpdateQueueAssignmentRequest;
import com.contactcenter.domain.exception.ResourceNotFoundException;

import java.util.Set;
import java.util.UUID;

/**
 * Serwis domenowy zarządzający przypisaniem agentów i grup do kolejki (BE-046).
 *
 * <p>Implementuje logikę GET i PUT /api/queues/{queueId}/assignment:
 * <ul>
 *   <li>Odczyt aktualnego stanu przypisania (flaga all_agents + listy bezpośrednich
 *       agentów i grup z enrichowanymi danymi)</li>
 *   <li>Podmiana przypisania – obsługa trybu allAgents=true (flaga) i
 *       allAgents=false (jawne listy agentów i grup)</li>
 *   <li>Walidacja: każdy directAgentId musi należeć do tenanta i mieć rolę AGENT;
 *       każdy groupId musi należeć do tenanta</li>
 * </ul>
 *
 * <p>Bezpieczeństwo:
 * <ul>
 *   <li>Wszystkie operacje filtrowane po tenantId z TenantContext</li>
 *   <li>{@code assertSameTenant} przed każdym zapisem przez repozytorium</li>
 *   <li>Walidacja cross-tenant dla agentów i grup (HTTP 400 zamiast 422 – per spec)</li>
 * </ul>
 */
public interface QueueAssignmentService {

    /**
     * Zwraca aktualny stan przypisania agentów i grup do kolejki.
     *
     * <p>Gdy flaga {@code all_agents=true}, listy są puste (routing obejmuje
     * wszystkich agentów tenanta, nie ma sensu ich wylistowywać w odpowiedzi).
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @return DTO z aktualnym stanem przypisania
     * @throws ResourceNotFoundException gdy kolejka nie istnieje lub nie należy do tenanta
     */
    QueueAssignmentResponse getAssignment(UUID queueId, UUID tenantId);

    /**
     * Podmienia przypisanie agentów i grup do kolejki.
     *
     * <p>Scenariusze:
     * <ul>
     *   <li>{@code allAgents=true} – ustawia flagę w tabeli {@code queue}; istniejące
     *       wiersze w {@code queue_agent} i {@code queue_agent_group} NIE są czyszczone
     *       (silnik routingu ignoruje je gdy flaga=true, a dane historyczne zostają)</li>
     *   <li>{@code allAgents=false} – wyłącza flagę i atomowo podmienia listy agentów i grup</li>
     * </ul>
     *
     * @param queueId  UUID kolejki
     * @param request  dane żądania z flagą allAgents i listami agentów/grup
     * @param tenantId UUID tenanta
     * @return DTO z nowym stanem przypisania
     * @throws ResourceNotFoundException gdy kolejka nie istnieje lub nie należy do tenanta
     * @throws IllegalArgumentException  gdy directAgentId lub groupId nie należy do tenanta
     *                                   lub agent nie ma roli AGENT (HTTP 400)
     */
    QueueAssignmentResponse updateAssignment(UUID queueId, UpdateQueueAssignmentRequest request, UUID tenantId);

    // =========================================================================
    // Metody delegujące (encapsulation pass – pkt 9 wzorca)
    // =========================================================================

    /**
     * Sprawdza flagę {@code all_agents} dla podanej kolejki.
     *
     * <p>Odpowiednik {@code QueueAssignmentRepository.isAllAgents}, używany
     * przez {@code RoutingService} przy rozwiązywaniu kwalifikujących się agentów.
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @return true gdy flaga all_agents = TRUE, false w pozostałych przypadkach
     */
    boolean isAllAgents(UUID queueId, UUID tenantId);

    /**
     * Zwraca pełny zbiór agentów kwalifikujących się do obsługi kolejki
     * (UNION bezpośrednich przypisań i przypisań przez grupy).
     *
     * <p>Odpowiednik {@code QueueAssignmentRepository.resolveEligibleAgentIds}, używany
     * przez {@code RoutingService}.
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @return deduplikowany zbiór UUID agentów; pusty gdy kolejka nie ma przypisań
     */
    Set<UUID> resolveEligibleAgentIds(UUID queueId, UUID tenantId);

    /**
     * Sprawdza czy podana grupa agentów jest przypisana do co najmniej jednej kolejki.
     *
     * <p>Odpowiednik {@code QueueAssignmentRepository.isGroupAssignedToAnyQueue}, używany
     * przez {@code AgentGroupService} przed usunięciem grupy.
     *
     * @param groupId  UUID grupy
     * @param tenantId UUID tenanta
     * @return true jeśli grupa jest przypisana do co najmniej jednej kolejki
     */
    boolean isGroupAssignedToAnyQueue(UUID groupId, UUID tenantId);
}
