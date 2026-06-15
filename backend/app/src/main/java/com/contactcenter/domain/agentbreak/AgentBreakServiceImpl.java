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

@Slf4j
@Service
@RequiredArgsConstructor
class AgentBreakServiceImpl implements AgentBreakService {

    private final AgentBreakRepository agentBreakRepository;

    // =========================================================================
    // Lista przerw
    // =========================================================================

    @Override
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

    @Override
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

    @Override
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

    @Override
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
