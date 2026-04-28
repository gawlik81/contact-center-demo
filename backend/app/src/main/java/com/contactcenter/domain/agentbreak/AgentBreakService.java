package com.contactcenter.domain.agentbreak;

import com.contactcenter.api.agentbreak.dto.AgentBreakResponse;
import com.contactcenter.api.agentbreak.dto.CreateAgentBreakRequest;
import com.contactcenter.api.agentbreak.dto.UpdateAgentBreakRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
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
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentBreakService {

    private final AgentBreakRepository agentBreakRepository;

    // =========================================================================
    // Lista przerw
    // =========================================================================

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
    public List<AgentBreakResponse> listBreaks(UUID agentId, UUID tenantId, Instant from, Instant to) {
        Instant resolvedFrom = (from != null) ? from : currentWeekStart();
        Instant resolvedTo   = (to   != null) ? to   : currentWeekEnd();

        log.debug("[AgentBreakService] Lista przerw: tenant={}, agentId={}, from={}, to={}",
                tenantId, agentId, resolvedFrom, resolvedTo);

        List<AgentBreak> breaks = agentBreakRepository.findByAgentIdAndStartTimeBetween(
                agentId, tenantId, resolvedFrom, resolvedTo);

        log.debug("[AgentBreakService] Znaleziono {} przerw dla agentId={}", breaks.size(), agentId);

        return breaks.stream().map(this::toResponse).toList();
    }

    // =========================================================================
    // Tworzenie przerwy
    // =========================================================================

    /**
     * Tworzy nową przerwę agenta w statusie PLANNED.
     *
     * @param request  dane nowej przerwy
     * @param agentId  UUID agenta (z tokenu JWT)
     * @param tenantId UUID tenanta (z tokenu JWT)
     * @return DTO nowo utworzonej przerwy
     * @throws IllegalArgumentException gdy endTime <= startTime lub startTime jest w przeszłości
     */
    public AgentBreakResponse createBreak(CreateAgentBreakRequest request, UUID agentId, UUID tenantId) {
        log.debug("[AgentBreakService] Tworzenie przerwy: tenant={}, agentId={}, type={}",
                tenantId, agentId, request.breakType());

        validateTimeRange(request.startTime(), request.endTime());
        validateStartTimeInFuture(request.startTime());

        AgentBreak agentBreak = new AgentBreak();
        agentBreak.setTenantId(tenantId);
        agentBreak.setAgentId(agentId);
        agentBreak.setStartTime(request.startTime());
        agentBreak.setEndTime(request.endTime());
        agentBreak.setBreakType(request.breakType().name());
        agentBreak.setNotes(request.notes());
        agentBreak.setStatus(BreakStatus.PLANNED.name());

        AgentBreak saved = agentBreakRepository.insert(agentBreak);

        log.info("[AgentBreakService] Przerwa utworzona: id={}, tenant={}, agentId={}",
                saved.getId(), tenantId, agentId);

        return toResponse(saved);
    }

    // =========================================================================
    // Edycja przerwy
    // =========================================================================

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
    public AgentBreakResponse updateBreak(UUID id, UpdateAgentBreakRequest request, UUID agentId, UUID tenantId) {
        log.debug("[AgentBreakService] Edycja przerwy: id={}, tenant={}, agentId={}", id, tenantId, agentId);

        AgentBreak agentBreak = findBreakOrThrow(id, tenantId);

        assertOwner(agentBreak, agentId, tenantId);
        assertEditable(agentBreak);

        validateTimeRange(request.startTime(), request.endTime());
        validateStartTimeInFuture(request.startTime());

        agentBreak.setStartTime(request.startTime());
        agentBreak.setEndTime(request.endTime());
        agentBreak.setBreakType(request.breakType().name());
        agentBreak.setNotes(request.notes());

        agentBreakRepository.update(agentBreak);

        // Pobierz zaktualizowany rekord, by zwrócić aktualne updatedAt
        AgentBreak refreshed = findBreakOrThrow(id, tenantId);

        log.info("[AgentBreakService] Przerwa zaktualizowana: id={}, tenant={}", id, tenantId);

        return toResponse(refreshed);
    }

    // =========================================================================
    // Anulowanie przerwy
    // =========================================================================

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
    public void cancelBreak(UUID id, UUID agentId, UUID tenantId) {
        log.debug("[AgentBreakService] Anulowanie przerwy: id={}, tenant={}, agentId={}", id, tenantId, agentId);

        AgentBreak agentBreak = findBreakOrThrow(id, tenantId);

        assertOwner(agentBreak, agentId, tenantId);
        assertEditable(agentBreak);

        agentBreakRepository.updateStatus(id, tenantId, BreakStatus.CANCELLED.name());

        log.info("[AgentBreakService] Przerwa anulowana: id={}, tenant={}", id, tenantId);
    }

    // =========================================================================
    // Metody pomocnicze – walidacja
    // =========================================================================

    /**
     * Weryfikuje, że endTime jest późniejszy niż startTime.
     *
     * @throws IllegalArgumentException gdy endTime <= startTime
     */
    private void validateTimeRange(Instant startTime, Instant endTime) {
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException(
                    "endTime musi być późniejszy niż startTime. startTime=" + startTime + ", endTime=" + endTime);
        }
    }

    /**
     * Weryfikuje, że startTime jest w przyszłości.
     *
     * @throws IllegalArgumentException gdy startTime <= teraz
     */
    private void validateStartTimeInFuture(Instant startTime) {
        if (!startTime.isAfter(Instant.now())) {
            throw new IllegalArgumentException(
                    "startTime musi być w przyszłości. Podano: " + startTime);
        }
    }

    /**
     * Weryfikuje, że zalogowany agent jest właścicielem przerwy.
     *
     * @throws CrossTenantAccessException gdy agentId != agentBreak.agentId (HTTP 403)
     */
    private void assertOwner(AgentBreak agentBreak, UUID agentId, UUID tenantId) {
        if (!agentBreak.getAgentId().equals(agentId)) {
            log.warn("[AgentBreakService] Odmowa dostępu: id={}, właściciel={}, żądający={}",
                    agentBreak.getId(), agentBreak.getAgentId(), agentId);
            throw new CrossTenantAccessException(agentBreak.getId(), tenantId);
        }
    }

    /**
     * Weryfikuje, że przerwa jest w statusie PLANNED (edytowalnym).
     *
     * @throws ConflictException gdy status to ACTIVE lub COMPLETED (HTTP 409)
     */
    private void assertEditable(AgentBreak agentBreak) {
        BreakStatus currentStatus = BreakStatus.valueOf(agentBreak.getStatus());
        if (currentStatus != BreakStatus.PLANNED) {
            throw new ConflictException(
                    "Nie można zmodyfikować przerwy w statusie " + currentStatus
                    + ". Edycja i anulowanie dozwolone wyłącznie dla przerw PLANNED. id=" + agentBreak.getId());
        }
    }

    /**
     * Pobiera przerwę lub rzuca {@link ResourceNotFoundException}.
     *
     * @throws ResourceNotFoundException gdy przerwa nie istnieje w tym tenancie (HTTP 404)
     */
    private AgentBreak findBreakOrThrow(UUID id, UUID tenantId) {
        return agentBreakRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Przerwa agenta nie istnieje: " + id));
    }

    // =========================================================================
    // Metody pomocnicze – daty i mapowanie
    // =========================================================================

    /**
     * Zwraca początek bieżącego tygodnia (poniedziałek 00:00:00 UTC).
     */
    private Instant currentWeekStart() {
        return Instant.now()
                .atOffset(ZoneOffset.UTC)
                .toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
    }

    /**
     * Zwraca koniec bieżącego tygodnia (niedziela 23:59:59.999999999 UTC).
     */
    private Instant currentWeekEnd() {
        return Instant.now()
                .atOffset(ZoneOffset.UTC)
                .toLocalDate()
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                .atTime(23, 59, 59, 999_999_999)
                .toInstant(ZoneOffset.UTC);
    }

    /**
     * Mapuje encję {@link AgentBreak} na DTO {@link AgentBreakResponse}.
     */
    private AgentBreakResponse toResponse(AgentBreak ab) {
        return new AgentBreakResponse(
                ab.getId(),
                ab.getAgentId(),
                ab.getStartTime(),
                ab.getEndTime(),
                ab.getBreakType(),
                ab.getNotes(),
                ab.getStatus(),
                ab.getCreatedAt(),
                ab.getUpdatedAt()
        );
    }
}
