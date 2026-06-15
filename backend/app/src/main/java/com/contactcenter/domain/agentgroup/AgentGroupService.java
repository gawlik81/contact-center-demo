package com.contactcenter.domain.agentgroup;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.agentgroup.dto.AgentGroupMembersResponse;
import com.contactcenter.api.agentgroup.dto.AgentGroupResponse;
import com.contactcenter.api.agentgroup.dto.CreateAgentGroupRequest;
import com.contactcenter.api.agentgroup.dto.UpdateAgentGroupRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.ResourceNotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Serwis zarządzający grupami agentów (BE-044).
 *
 * <p>Odpowiada za logikę biznesową CRUD grup agentów i zarządzanie ich członkostwem.
 * Izolacja multi-tenant jest zapewniana przez przekazanie tenantId do każdej
 * metody repozytorium i weryfikację przez RLS w PostgreSQL.
 *
 * <p>Polityki biznesowe:
 * <ul>
 *   <li>Nazwa grupy musi być unikalna w obrębie tenanta – duplikat zwraca HTTP 409</li>
 *   <li>Nie można usunąć grupy przypisanej do kolejki – zwraca HTTP 409</li>
 *   <li>Członkami grupy mogą być wyłącznie agenci z rolą AGENT – SUPERVISOR/ADMIN są odrzucani</li>
 *   <li>Agent musi należeć do tego samego tenanta – obcy agentId zwraca HTTP 422</li>
 * </ul>
 */
public interface AgentGroupService {

    /**
     * Zwraca paginowaną listę grup agentów tenanta z liczbą członków.
     *
     * @param tenantId UUID tenanta
     * @param name     opcjonalny filtr nazwy (ILIKE), null = bez filtru
     * @param page     numer strony (0-based)
     * @param size     rozmiar strony
     * @return strona wyników opakowana w {@link PagedResponse}
     */
    PagedResponse<AgentGroupResponse> listGroups(UUID tenantId, String name, int page, int size);

    /**
     * Tworzy nową grupę agentów.
     *
     * @param request  dane grupy (nazwa)
     * @param tenantId UUID tenanta
     * @return DTO nowo utworzonej grupy
     * @throws ConflictException gdy nazwa jest już zajęta w tym tenancie (HTTP 409)
     */
    AgentGroupResponse createGroup(CreateAgentGroupRequest request, UUID tenantId);

    /**
     * Aktualizuje nazwę grupy agentów.
     *
     * @param groupId  UUID grupy
     * @param request  nowe dane grupy
     * @param tenantId UUID tenanta
     * @return DTO zaktualizowanej grupy
     * @throws ResourceNotFoundException gdy grupa nie istnieje (HTTP 404)
     */
    AgentGroupResponse updateGroup(UUID groupId, UpdateAgentGroupRequest request, UUID tenantId);

    /**
     * Usuwa grupę agentów.
     *
     * @param groupId  UUID grupy
     * @param tenantId UUID tenanta
     * @throws ConflictException         gdy grupa jest przypisana do kolejki (HTTP 409)
     * @throws ResourceNotFoundException gdy grupa nie istnieje (HTTP 404)
     */
    void deleteGroup(UUID groupId, UUID tenantId);

    /**
     * Zwraca listę członków grupy agentów.
     *
     * @param groupId  UUID grupy
     * @param tenantId UUID tenanta
     * @return DTO z listą agentów należących do grupy
     * @throws ResourceNotFoundException gdy grupa nie istnieje (HTTP 404)
     */
    AgentGroupMembersResponse getMembers(UUID groupId, UUID tenantId);

    /**
     * Podmienia (atomowo) wszystkich członków grupy na nową listę agentów.
     *
     * <p>Walidacje:
     * <ul>
     *   <li>Każdy agentId musi należeć do tego samego tenanta i nie być usunięty</li>
     *   <li>Każdy agent musi mieć rolę AGENT (nie SUPERVISOR ani ADMIN)</li>
     * </ul>
     *
     * @param groupId   UUID grupy
     * @param agentIds  nowy zestaw UUID agentów (może być pusta lista)
     * @param tenantId  UUID tenanta
     * @return DTO z nową listą członków grupy
     * @throws ResourceNotFoundException gdy grupa nie istnieje (HTTP 404)
     * @throws IllegalArgumentException  gdy agentId nie należy do tenanta lub ma nieprawidłową rolę (HTTP 422)
     */
    AgentGroupMembersResponse replaceMembers(UUID groupId, List<UUID> agentIds, UUID tenantId);

    /**
     * Zwraca skrótowe dane grupy agentów (nazwa + liczba członków) używane przy
     * cross-domain wzbogacaniu przypisań kampanii/kolejek o informacje o grupie.
     *
     * @param tenantId UUID tenanta
     * @param groupId  UUID grupy
     * @return {@link Optional} z podsumowaniem grupy lub empty, gdy grupa nie istnieje
     *         lub nie należy do tenanta
     */
    Optional<AgentGroupOverview> findGroupSummary(UUID tenantId, UUID groupId);
}
