package com.contactcenter.domain.campaign;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.campaign.dto.CampaignResponse;
import com.contactcenter.api.campaign.dto.CreateCampaignRequest;
import com.contactcenter.api.campaign.dto.UpdateCampaignRequest;
import com.contactcenter.domain.exception.InvalidOperationException;
import jakarta.persistence.EntityNotFoundException;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Serwis domenowy zarządzający kampaniami wychodzącymi.
 *
 * <p>Pilnuje dozwolonych przejść statusów kampanii:
 * <pre>
 *   DRAFT      → SCHEDULED (start, jeśli start_date w przyszłości)
 *   DRAFT      → RUNNING   (start, jeśli harmonogram pusty lub aktualny)
 *   SCHEDULED  → RUNNING   (start)
 *   SCHEDULED  → DRAFT     (revertToDraft)
 *   RUNNING    → PAUSED    (pause)
 *   PAUSED     → RUNNING   (start/resume)
 *   RUNNING    → STOPPED   (stop)
 *   PAUSED     → STOPPED   (stop)
 *   SCHEDULED  → STOPPED   (stop)
 * </pre>
 *
 * <p>Waliduje harmonogram JSONB (end_date >= start_date, time_to > time_from).
 * Start kampanii poza oknem czasowym → {@link InvalidOperationException}.
 * Start kampanii bez kontaktów → {@link InvalidOperationException}.
 */
public interface CampaignService {

    /**
     * Tworzy nową kampanię w statusie DRAFT.
     *
     * @param request  dane nowej kampanii
     * @param tenantId UUID tenanta
     * @param userId   UUID użytkownika tworzącego kampanię
     * @return DTO nowo utworzonej kampanii
     */
    CampaignResponse createCampaign(CreateCampaignRequest request, UUID tenantId, UUID userId);

    /**
     * Zwraca stronicowaną listę kampanii tenanta.
     *
     * @param tenantId UUID tenanta
     * @param page     numer strony (0-based)
     * @param size     rozmiar strony
     * @return stronicowana lista kampanii
     */
    PagedResponse<CampaignResponse> listCampaigns(UUID tenantId, int page, int size);

    /**
     * Pobiera szczegóły kampanii.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return DTO kampanii
     * @throws EntityNotFoundException gdy kampania nie istnieje lub należy do innego tenanta
     */
    CampaignResponse getCampaign(UUID campaignId, UUID tenantId);

    /**
     * Aktualizuje kampanię w statusie DRAFT (PATCH semantics – null pola są ignorowane).
     *
     * @param campaignId UUID kampanii
     * @param request    dane do aktualizacji (null = nie zmieniaj)
     * @param tenantId   UUID tenanta
     * @return DTO zaktualizowanej kampanii
     * @throws EntityNotFoundException   gdy kampania nie istnieje
     * @throws InvalidOperationException gdy kampania nie jest w statusie DRAFT
     */
    CampaignResponse updateCampaign(UUID campaignId, UpdateCampaignRequest request, UUID tenantId);

    /**
     * Uruchamia kampanię.
     *
     * <p>Dozwolone przejścia: DRAFT → SCHEDULED/RUNNING, SCHEDULED → RUNNING, PAUSED → RUNNING.
     * Waliduje harmonogram i obecność kontaktów.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return DTO kampanii z nowym statusem
     * @throws InvalidOperationException gdy przejście jest niedozwolone lub kampania nie spełnia warunków
     */
    CampaignResponse startCampaign(UUID campaignId, UUID tenantId);

    /**
     * Wstrzymuje kampanię RUNNING.
     *
     * <p>Dozwolone przejście: RUNNING → PAUSED.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return DTO kampanii z nowym statusem
     * @throws InvalidOperationException gdy kampania nie jest w statusie RUNNING
     */
    CampaignResponse pauseCampaign(UUID campaignId, UUID tenantId);

    /**
     * Zatrzymuje kampanię.
     *
     * <p>Dozwolone przejścia: RUNNING → STOPPED, PAUSED → STOPPED, SCHEDULED → STOPPED.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return DTO kampanii z nowym statusem
     * @throws InvalidOperationException gdy przejście jest niedozwolone
     */
    CampaignResponse stopCampaign(UUID campaignId, UUID tenantId);

    /**
     * Cofa kampanię SCHEDULED do statusu DRAFT.
     *
     * <p>Dozwolone przejście: SCHEDULED → DRAFT.
     * Umożliwia edycję harmonogramu po zaplanowaniu.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return DTO kampanii ze statusem DRAFT
     * @throws InvalidOperationException gdy kampania nie jest w statusie SCHEDULED
     */
    CampaignResponse revertToDraft(UUID campaignId, UUID tenantId);

    /**
     * Sprawdza czy nazwa kampanii jest już zajęta w ramach tenanta.
     *
     * <p>Deleguje do repozytorium – porównanie case-insensitive. Przy edycji istniejącej
     * kampanii przekaż jej {@code excludeId}, żeby nazwa nie kolidowała sama ze sobą.
     *
     * @param name      nazwa do sprawdzenia
     * @param tenantId  UUID tenanta
     * @param excludeId UUID kampanii wykluczanej z porównania (null = tryb tworzenia)
     * @return {@code true} jeśli nazwa zajęta, {@code false} jeśli dostępna
     */
    boolean isNameTaken(String name, UUID tenantId, UUID excludeId);

    // =========================================================================
    // Dostęp do encji (encapsulation pass – pkt 9 wzorca)
    // =========================================================================

    /**
     * Pobiera encję kampanii po ID z zabezpieczeniem cross-tenant.
     *
     * <p>W odróżnieniu od {@link #getCampaign}, zwraca encję domenową {@link Campaign}
     * (nie DTO) – do użytku przez inne serwisy/komponenty domenowe.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return Optional z encją kampanii lub empty gdy nie istnieje lub należy do innego tenanta
     */
    Optional<Campaign> findCampaignEntity(UUID campaignId, UUID tenantId);

    /**
     * Pobiera wiele kampanii naraz po zbiorze ID – batch lookup bez problemu N+1.
     *
     * @param ids      zbiór UUID kampanii do pobrania (może być pusty)
     * @param tenantId UUID tenanta – wymagany do izolacji danych
     * @return lista kampanii spełniających warunki (kolejność niegwarantowana)
     */
    List<Campaign> findCampaignsByIds(Collection<UUID> ids, UUID tenantId);

    /**
     * Pobiera kampanie powiązane z agentem przez kolejkę (kalendarz agenta, BE-051).
     *
     * @param tenantId UUID tenanta
     * @param agentId  UUID agenta
     * @return lista kampanii widocznych dla agenta (bez COMPLETED/CANCELLED)
     */
    List<Campaign> getCampaignsForAgentCalendar(UUID tenantId, UUID agentId);

    /**
     * Pobiera wszystkie kampanie w statusie RUNNING dla danego tenanta (wszystkie typy dialera).
     *
     * @param tenantId UUID tenanta
     * @return lista kampanii w statusie RUNNING
     */
    List<Campaign> getRunningCampaigns(UUID tenantId);

    /**
     * Pobiera kampanie RUNNING z typem dialera MANUAL dla danego tenanta.
     *
     * @param tenantId UUID tenanta
     * @return lista kampanii w statusie RUNNING z dialer_type = 'MANUAL'
     */
    List<Campaign> getRunningManualCampaigns(UUID tenantId);

    /**
     * Zlicza łączną liczbę kampanii oraz liczbę kampanii RUNNING dla tenanta, w jednym zapytaniu.
     *
     * <p>Używane przez {@code AdminMetricsService} (SUPER_ADMIN, {@code GET /api/admin/metrics/usage}).
     *
     * @param tenantId UUID tenanta
     * @return {@code Object[]} {total(Number), running(Number)}
     */
    Object[] countTotalAndRunningByTenantId(UUID tenantId);
}
