package com.contactcenter.domain.agentbreak;

import com.contactcenter.api.agentbreak.dto.AgentBreakResponse;
import com.contactcenter.api.agentbreak.dto.CreateAgentBreakRequest;
import com.contactcenter.api.agentbreak.dto.UpdateAgentBreakRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.domain.exception.ResourceNotFoundException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Serwis zarządzający przerwami agentów (BE-050).
 *
 * <p>Odpowiada za logikę biznesową CRUD przerw agentów.
 * Izolacja multi-tenant jest zapewniana przez przekazanie tenantId do każdej
 * metody repozytorium i weryfikację przez RLS w PostgreSQL.
 *
 * <p>Polityki biznesowe:
 * <ul>
 *   <li>endTime musi być późniejszy niż startTime – naruszenie zwraca HTTP 400</li>
 *   <li>startTime musi być w przyszłości – naruszenie zwraca HTTP 400</li>
 *   <li>Edycja i anulowanie tylko przez właściciela przerwy – inny agentId zwraca HTTP 403</li>
 *   <li>Edycja jest dozwolona wyłącznie dla statusu PLANNED – ACTIVE/COMPLETED zwraca HTTP 409</li>
 *   <li>Anulowanie zmienia status PLANNED → CANCELLED (soft delete)</li>
 *   <li>Brak zakresu dat w listowaniu → domyślnie bieżący tydzień (poniedziałek–niedziela UTC)</li>
 * </ul>
 */
public interface AgentBreakService {

    /**
     * Zwraca listę przerw agenta w podanym zakresie dat.
     *
     * <p>Gdy {@code from} lub {@code to} jest null, zakres jest uzupełniany
     * do bieżącego tygodnia (poniedziałek 00:00 UTC – niedziela 23:59:59 UTC).
     *
     * @param agentId  UUID agenta, którego przerwy są pobierane
     * @param tenantId UUID tenanta
     * @param from     początek zakresu (opcjonalny, null = bieżący tydzień)
     * @param to       koniec zakresu (opcjonalny, null = bieżący tydzień)
     * @return lista przerw posortowana po startTime ASC
     */
    List<AgentBreakResponse> listBreaks(UUID agentId, UUID tenantId, Instant from, Instant to);

    /**
     * Tworzy nową przerwę agenta w statusie PLANNED.
     *
     * @param request  dane nowej przerwy
     * @param agentId  UUID agenta (z tokenu JWT)
     * @param tenantId UUID tenanta (z tokenu JWT)
     * @return DTO nowo utworzonej przerwy
     * @throws IllegalArgumentException gdy endTime <= startTime lub startTime jest w przeszłości
     */
    AgentBreakResponse createBreak(CreateAgentBreakRequest request, UUID agentId, UUID tenantId);

    /**
     * Edytuje istniejącą przerwę agenta.
     *
     * <p>Edycja jest dozwolona wyłącznie dla przerw w statusie PLANNED.
     * Agent może edytować tylko swoje własne przerwy.
     *
     * @param id       UUID przerwy do edycji
     * @param request  nowe dane przerwy
     * @param agentId  UUID agenta z tokenu JWT (właściciel)
     * @param tenantId UUID tenanta z tokenu JWT
     * @return DTO zaktualizowanej przerwy
     * @throws ResourceNotFoundException   gdy przerwa nie istnieje (HTTP 404)
     * @throws CrossTenantAccessException  gdy agent nie jest właścicielem przerwy (HTTP 403)
     * @throws ConflictException           gdy status przerwy to ACTIVE lub COMPLETED (HTTP 409)
     * @throws IllegalArgumentException    gdy endTime <= startTime lub startTime w przeszłości (HTTP 400)
     */
    AgentBreakResponse updateBreak(UUID id, UpdateAgentBreakRequest request, UUID agentId, UUID tenantId);

    /**
     * Anuluje przerwę agenta przez zmianę statusu PLANNED → CANCELLED.
     *
     * <p>Agent może anulować tylko swoje własne przerwy.
     *
     * @param id       UUID przerwy do anulowania
     * @param agentId  UUID agenta z tokenu JWT (właściciel)
     * @param tenantId UUID tenanta z tokenu JWT
     * @throws ResourceNotFoundException  gdy przerwa nie istnieje (HTTP 404)
     * @throws CrossTenantAccessException gdy agent nie jest właścicielem przerwy (HTTP 403)
     * @throws ConflictException          gdy status przerwy nie jest PLANNED (HTTP 409)
     */
    void cancelBreak(UUID id, UUID agentId, UUID tenantId);
}
