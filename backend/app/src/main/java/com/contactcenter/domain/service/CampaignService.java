package com.contactcenter.domain.service;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.campaign.dto.CampaignResponse;
import com.contactcenter.api.campaign.dto.CreateCampaignRequest;
import com.contactcenter.api.campaign.dto.UpdateCampaignRequest;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.domain.model.Campaign;
import com.contactcenter.domain.repository.CampaignRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Serwis domenowy zarządzający kampaniami wychodzącymi.
 *
 * <p>Pilnuje dozwolonych przejść statusów kampanii:
 * <pre>
 *   DRAFT      → SCHEDULED (start, jeśli start_date w przyszłości)
 *   DRAFT      → RUNNING   (start, jeśli harmonogram pusty lub aktualny)
 *   SCHEDULED  → RUNNING   (start)
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
@Slf4j
@Service
@RequiredArgsConstructor
public class CampaignService {

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_SCHEDULED = "SCHEDULED";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_PAUSED = "PAUSED";
    private static final String STATUS_STOPPED = "STOPPED";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private final CampaignRepository campaignRepository;

    // =========================================================================
    // CRUD
    // =========================================================================

    /**
     * Tworzy nową kampanię w statusie DRAFT.
     *
     * @param request  dane nowej kampanii
     * @param tenantId UUID tenanta
     * @param userId   UUID użytkownika tworzącego kampanię
     * @return DTO nowo utworzonej kampanii
     */
    @Transactional
    public CampaignResponse createCampaign(CreateCampaignRequest request, UUID tenantId, UUID userId) {
        validateSchedule(request.schedule());

        Campaign campaign = Campaign.builder()
                .tenantId(tenantId)
                .name(request.name())
                .type(request.type() != null ? request.type() : "OUTBOUND_VOICE")
                .dialerType(request.dialerType() != null ? request.dialerType() : "PROGRESSIVE")
                .schedule(request.schedule() != null ? request.schedule() : new HashMap<>())
                .status(STATUS_DRAFT)
                .queueId(request.queueId())
                .dispositionCodes(request.dispositionCodes() != null ? request.dispositionCodes() : new ArrayList<>())
                .maxAttempts(request.maxAttempts() != null ? request.maxAttempts() : 3)
                .retryDelayMinutes(request.retryDelayMinutes() != null ? request.retryDelayMinutes() : 60)
                .createdBy(userId)
                .build();

        Campaign saved = campaignRepository.save(campaign);
        log.info("[CampaignService] Utworzono kampanię: campaignId={}, name='{}', tenant={}",
                saved.getCampaignId(), saved.getName(), tenantId);

        return CampaignResponse.from(saved);
    }

    /**
     * Zwraca stronicowaną listę kampanii tenanta.
     *
     * @param tenantId UUID tenanta
     * @param page     numer strony (0-based)
     * @param size     rozmiar strony
     * @return stronicowana lista kampanii
     */
    @Transactional(readOnly = true)
    public PagedResponse<CampaignResponse> listCampaigns(UUID tenantId, int page, int size) {
        List<Campaign> campaigns = campaignRepository.findByTenantId(tenantId, page, size);
        long total = campaignRepository.countByTenantId(tenantId);

        List<CampaignResponse> content = campaigns.stream()
                .map(CampaignResponse::from)
                .toList();

        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;

        return new PagedResponse<>(
                content,
                page,
                size,
                total,
                totalPages,
                page == 0,
                page >= totalPages - 1
        );
    }

    /**
     * Pobiera szczegóły kampanii.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return DTO kampanii
     * @throws EntityNotFoundException gdy kampania nie istnieje lub należy do innego tenanta
     */
    @Transactional(readOnly = true)
    public CampaignResponse getCampaign(UUID campaignId, UUID tenantId) {
        Campaign campaign = findOrThrow(campaignId, tenantId);
        return CampaignResponse.from(campaign);
    }

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
    @Transactional
    public CampaignResponse updateCampaign(UUID campaignId, UpdateCampaignRequest request, UUID tenantId) {
        Campaign campaign = findOrThrow(campaignId, tenantId);

        if (!STATUS_DRAFT.equals(campaign.getStatus())) {
            throw new InvalidOperationException(
                    String.format("Kampania '%s' nie może być edytowana – aktualny status: %s. " +
                            "Edycja dozwolona tylko w statusie DRAFT.", campaign.getName(), campaign.getStatus()));
        }

        if (request.schedule() != null) {
            validateSchedule(request.schedule());
            campaign.setSchedule(request.schedule());
        }
        if (request.name() != null) {
            campaign.setName(request.name());
        }
        if (request.queueId() != null) {
            campaign.setQueueId(request.queueId());
        }
        if (request.dispositionCodes() != null) {
            campaign.setDispositionCodes(request.dispositionCodes());
        }
        if (request.maxAttempts() != null) {
            campaign.setMaxAttempts(request.maxAttempts());
        }
        if (request.retryDelayMinutes() != null) {
            campaign.setRetryDelayMinutes(request.retryDelayMinutes());
        }

        Campaign saved = campaignRepository.save(campaign);
        log.info("[CampaignService] Zaktualizowano kampanię: campaignId={}, tenant={}", campaignId, tenantId);

        return CampaignResponse.from(saved);
    }

    // =========================================================================
    // Zarządzanie statusem
    // =========================================================================

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
    @Transactional
    public CampaignResponse startCampaign(UUID campaignId, UUID tenantId) {
        Campaign campaign = findOrThrow(campaignId, tenantId);
        String currentStatus = campaign.getStatus();

        // Walidacja dozwolonego przejścia
        if (!STATUS_DRAFT.equals(currentStatus)
                && !STATUS_SCHEDULED.equals(currentStatus)
                && !STATUS_PAUSED.equals(currentStatus)) {
            throw new InvalidOperationException(
                    String.format("Nie można uruchomić kampanii '%s' ze statusu %s. " +
                            "Dozwolone stany do uruchomienia: DRAFT, SCHEDULED, PAUSED.",
                            campaign.getName(), currentStatus));
        }

        // Walidacja: kampania musi mieć kontakty (nie dotyczy wznowienia z PAUSED)
        if (!STATUS_PAUSED.equals(currentStatus)) {
            long contactCount = campaignRepository.countContacts(campaignId, tenantId);
            if (contactCount == 0) {
                throw new InvalidOperationException(
                        String.format("Kampania '%s' nie ma listy kontaktów. " +
                                "Dodaj kontakty przed uruchomieniem kampanii.", campaign.getName()));
            }
        }

        // Wyznaczenie nowego statusu na podstawie harmonogramu
        String newStatus = resolveStartStatus(campaign);

        campaign.setStatus(newStatus);
        Campaign saved = campaignRepository.save(campaign);

        log.info("[CampaignService] Uruchomiono kampanię: campaignId={}, {} → {}, tenant={}",
                campaignId, currentStatus, newStatus, tenantId);

        return CampaignResponse.from(saved);
    }

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
    @Transactional
    public CampaignResponse pauseCampaign(UUID campaignId, UUID tenantId) {
        Campaign campaign = findOrThrow(campaignId, tenantId);
        String currentStatus = campaign.getStatus();

        if (!STATUS_RUNNING.equals(currentStatus)) {
            throw new InvalidOperationException(
                    String.format("Nie można wstrzymać kampanii '%s' ze statusu %s. " +
                            "Wstrzymanie dozwolone tylko dla kampanii w statusie RUNNING.",
                            campaign.getName(), currentStatus));
        }

        campaign.setStatus(STATUS_PAUSED);
        Campaign saved = campaignRepository.save(campaign);

        log.info("[CampaignService] Wstrzymano kampanię: campaignId={}, RUNNING → PAUSED, tenant={}",
                campaignId, tenantId);

        return CampaignResponse.from(saved);
    }

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
    @Transactional
    public CampaignResponse stopCampaign(UUID campaignId, UUID tenantId) {
        Campaign campaign = findOrThrow(campaignId, tenantId);
        String currentStatus = campaign.getStatus();

        if (!STATUS_RUNNING.equals(currentStatus)
                && !STATUS_PAUSED.equals(currentStatus)
                && !STATUS_SCHEDULED.equals(currentStatus)) {
            throw new InvalidOperationException(
                    String.format("Nie można zatrzymać kampanii '%s' ze statusu %s. " +
                            "Zatrzymanie dozwolone dla kampanii w statusie: RUNNING, PAUSED, SCHEDULED.",
                            campaign.getName(), currentStatus));
        }

        campaign.setStatus(STATUS_STOPPED);
        Campaign saved = campaignRepository.save(campaign);

        log.info("[CampaignService] Zatrzymano kampanię: campaignId={}, {} → STOPPED, tenant={}",
                campaignId, currentStatus, tenantId);

        return CampaignResponse.from(saved);
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    /**
     * Wyznacza docelowy status po operacji "start" na podstawie harmonogramu kampanii.
     *
     * <p>Logika:
     * <ul>
     *   <li>Brak harmonogramu ({}) → RUNNING</li>
     *   <li>start_date w przyszłości → SCHEDULED</li>
     *   <li>end_date w przeszłości → błąd 422</li>
     *   <li>Kampania poza oknem active_hours/active_days → błąd 422</li>
     *   <li>Kampania w oknie czasowym → RUNNING</li>
     * </ul>
     *
     * @param campaign kampania do uruchomienia
     * @return SCHEDULED lub RUNNING
     * @throws InvalidOperationException gdy kampania poza oknem harmonogramu
     */
    private String resolveStartStatus(Campaign campaign) {
        Map<String, Object> schedule = campaign.getSchedule();

        // Brak harmonogramu – uruchamiaj zawsze
        if (schedule == null || schedule.isEmpty()) {
            return STATUS_RUNNING;
        }

        ZoneId zoneId = resolveTimezone(schedule);
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        LocalDate today = now.toLocalDate();

        // Sprawdź start_date
        String startDateStr = (String) schedule.get("start_date");
        if (startDateStr != null) {
            LocalDate startDate = LocalDate.parse(startDateStr);
            if (startDate.isAfter(today)) {
                // Start w przyszłości → SCHEDULED
                return STATUS_SCHEDULED;
            }
        }

        // Sprawdź end_date
        String endDateStr = (String) schedule.get("end_date");
        if (endDateStr != null) {
            LocalDate endDate = LocalDate.parse(endDateStr);
            if (endDate.isBefore(today)) {
                throw new InvalidOperationException(
                        String.format("Kampania '%s' poza oknem czasowym harmonogramu: " +
                                "data zakończenia %s jest w przeszłości.", campaign.getName(), endDateStr));
            }
        }

        // Sprawdź active_days
        @SuppressWarnings("unchecked")
        List<String> activeDays = (List<String>) schedule.get("active_days");
        if (activeDays != null && !activeDays.isEmpty()) {
            String currentDayOfWeek = now.getDayOfWeek().name().substring(0, 3); // MON, TUE, ...
            if (!activeDays.contains(currentDayOfWeek)) {
                throw new InvalidOperationException(
                        String.format("Kampania '%s' poza oknem czasowym harmonogramu: " +
                                "dzisiejszy dzień tygodnia (%s) nie jest w liście aktywnych dni.",
                                campaign.getName(), currentDayOfWeek));
            }
        }

        // Sprawdź active_hours
        @SuppressWarnings("unchecked")
        Map<String, Object> activeHours = (Map<String, Object>) schedule.get("active_hours");
        if (activeHours != null) {
            String fromStr = (String) activeHours.get("from");
            String toStr = (String) activeHours.get("to");
            if (fromStr != null && toStr != null) {
                LocalTime fromTime = LocalTime.parse(fromStr);
                LocalTime toTime = LocalTime.parse(toStr);
                LocalTime currentTime = now.toLocalTime();
                if (currentTime.isBefore(fromTime) || currentTime.isAfter(toTime)) {
                    throw new InvalidOperationException(
                            String.format("Kampania '%s' poza oknem czasowym harmonogramu: " +
                                    "aktualna godzina %s jest poza oknem %s–%s.",
                                    campaign.getName(), currentTime, fromStr, toStr));
                }
            }
        }

        return STATUS_RUNNING;
    }

    /**
     * Waliduje spójność harmonogramu kampanii.
     *
     * @param schedule mapa JSONB harmonogramu (może być null lub pusta)
     * @throws InvalidOperationException gdy harmonogram jest niespójny
     */
    private void validateSchedule(Map<String, Object> schedule) {
        if (schedule == null || schedule.isEmpty()) {
            return;
        }

        String startDateStr = (String) schedule.get("start_date");
        String endDateStr = (String) schedule.get("end_date");

        if (startDateStr != null && endDateStr != null) {
            LocalDate startDate;
            LocalDate endDate;
            try {
                startDate = LocalDate.parse(startDateStr);
                endDate = LocalDate.parse(endDateStr);
            } catch (Exception e) {
                throw new InvalidOperationException(
                        "Nieprawidłowy format daty w harmonogramie. Oczekiwany format: YYYY-MM-DD.");
            }
            if (endDate.isBefore(startDate)) {
                throw new InvalidOperationException(
                        String.format("Nieprawidłowy harmonogram: end_date (%s) musi być >= start_date (%s).",
                                endDateStr, startDateStr));
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> activeHours = (Map<String, Object>) schedule.get("active_hours");
        if (activeHours != null) {
            String fromStr = (String) activeHours.get("from");
            String toStr = (String) activeHours.get("to");
            if (fromStr != null && toStr != null) {
                LocalTime fromTime;
                LocalTime toTime;
                try {
                    fromTime = LocalTime.parse(fromStr);
                    toTime = LocalTime.parse(toStr);
                } catch (Exception e) {
                    throw new InvalidOperationException(
                            "Nieprawidłowy format godziny w active_hours. Oczekiwany format: HH:mm.");
                }
                if (!toTime.isAfter(fromTime)) {
                    throw new InvalidOperationException(
                            String.format("Nieprawidłowy harmonogram: active_hours.to (%s) musi być > active_hours.from (%s).",
                                    toStr, fromStr));
                }
            }
        }
    }

    /**
     * Pobiera strefę czasową z harmonogramu. Domyślnie: Europe/Warsaw.
     */
    private ZoneId resolveTimezone(Map<String, Object> schedule) {
        String timezone = (String) schedule.get("timezone");
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of("Europe/Warsaw");
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            log.warn("[CampaignService] Nieznana strefa czasowa '{}', używam Europe/Warsaw", timezone);
            return ZoneId.of("Europe/Warsaw");
        }
    }

    /**
     * Pobiera kampanię lub rzuca EntityNotFoundException.
     */
    private Campaign findOrThrow(UUID campaignId, UUID tenantId) {
        return campaignRepository.findById(campaignId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        String.format("Kampania o ID %s nie istnieje lub brak dostępu.", campaignId)));
    }
}
