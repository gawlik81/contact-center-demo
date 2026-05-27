package com.contactcenter.domain.disposition;

import com.contactcenter.domain.disposition.dto.AvailableDispositionDto;
import com.contactcenter.domain.disposition.dto.CreateCustomDispositionRequest;
import com.contactcenter.domain.disposition.dto.CustomDispositionDto;
import com.contactcenter.domain.disposition.dto.UpdateCustomDispositionRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Serwis zarządzający własnymi dyspozycjami po kontakcie (BE-092).
 *
 * <p>Odpowiada za:
 * <ul>
 *   <li>Resolucję dostępnych dyspozycji dla agenta według priorytetu: kampania → kolejka → system</li>
 *   <li>CRUD dyspozycji per kampania i per kolejka (widok supervisora)</li>
 * </ul>
 *
 * <p>Systemowe dyspozycje domyślne ({@link #SYSTEM_DEFAULTS}) są zwracane gdy żaden custom
 * zestaw nie jest skonfigurowany — gwarantuje to nigdy niepustą listę dyspozycji dla agenta.
 *
 * <p>Izolacja multi-tenant przez przekazanie {@code tenantId} do każdej metody repozytorium.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomDispositionService {

    private final CustomDispositionRepository customDispositionRepository;

    /**
     * Systemowe dyspozycje domyślne używane gdy żaden tenant nie skonfigurował własnych.
     *
     * <p>Pokrywają podstawowe wyniki rozmowy wychodzącego call center.
     * Kolejność (ordinal 1-6) odzwierciedla częstotliwość użycia w typowych kampaniach.
     */
    static final List<AvailableDispositionDto> SYSTEM_DEFAULTS = List.of(
            new AvailableDispositionDto("SALE",         "Sprzedaż",             "positive", 1),
            new AvailableDispositionDto("NO_INTEREST",  "Brak zainteresowania", "negative", 2),
            new AvailableDispositionDto("CALLBACK",     "Oddzwonienie",         "warning",  3),
            new AvailableDispositionDto("WRONG_NUMBER", "Zły numer",            "neutral",  4),
            new AvailableDispositionDto("TECH_ISSUE",   "Problem techniczny",   "neutral",  5),
            new AvailableDispositionDto("OTHER",        "Inne",                 "neutral",  6)
    );

    // =========================================================================
    // Resolucja dyspozycji (dla agenta)
    // =========================================================================

    /**
     * Zwraca dostępne dyspozycje dla kontaktu według priorytetu: kampania → kolejka → system.
     *
     * <p>Priorytet resolucji:
     * <ol>
     *   <li>Jeśli {@code campaignId != null} i kampania ma skonfigurowane aktywne dyspozycje →
     *       zwróć dyspozycje kampanii</li>
     *   <li>Else jeśli {@code queueId != null} i kolejka ma skonfigurowane aktywne dyspozycje →
     *       zwróć dyspozycje kolejki</li>
     *   <li>Else → zwróć {@link #SYSTEM_DEFAULTS}</li>
     * </ol>
     *
     * <p>Metoda nigdy nie zwraca pustej listy.
     *
     * @param campaignId UUID kampanii (nullable)
     * @param queueId    UUID kolejki (nullable)
     * @param tenantId   UUID tenanta
     * @return niepusta lista dostępnych dyspozycji dla agenta
     */
    public List<AvailableDispositionDto> resolveForContact(UUID campaignId, UUID queueId, UUID tenantId) {
        log.debug("[CustomDispositionService] resolveForContact: campaign={}, queue={}, tenant={}",
                campaignId, queueId, tenantId);

        if (campaignId != null && customDispositionRepository.existsByCampaignId(campaignId, tenantId)) {
            log.debug("[CustomDispositionService] Resolucja: dyspozycje kampanii campaign={}", campaignId);
            return customDispositionRepository.findByCampaignId(campaignId, tenantId)
                    .stream()
                    .map(this::mapToAvailable)
                    .toList();
        }

        if (queueId != null && customDispositionRepository.existsByQueueId(queueId, tenantId)) {
            log.debug("[CustomDispositionService] Resolucja: dyspozycje kolejki queue={}", queueId);
            return customDispositionRepository.findByQueueId(queueId, tenantId)
                    .stream()
                    .map(this::mapToAvailable)
                    .toList();
        }

        log.debug("[CustomDispositionService] Resolucja: systemowe domyślne dyspozycje");
        return SYSTEM_DEFAULTS;
    }

    // =========================================================================
    // CRUD per kampania (supervisor)
    // =========================================================================

    /**
     * Zwraca listę wszystkich dyspozycji (włącznie z nieaktywnymi) dla kampanii.
     *
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return lista dyspozycji posortowana po ordinal ASC
     */
    public List<CustomDispositionDto> listForCampaign(UUID campaignId, UUID tenantId) {
        log.debug("[CustomDispositionService] listForCampaign: campaign={}, tenant={}", campaignId, tenantId);

        return customDispositionRepository.findAllByCampaignId(campaignId, tenantId)
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    /**
     * Tworzy nową dyspozycję przypisaną do kampanii.
     *
     * @param campaignId UUID kampanii
     * @param req        dane nowej dyspozycji
     * @param tenantId   UUID tenanta
     * @return DTO nowo utworzonej dyspozycji
     * @throws ConflictException gdy kod dyspozycji już istnieje w tej kampanii (HTTP 409)
     */
    public CustomDispositionDto createForCampaign(UUID campaignId, CreateCustomDispositionRequest req, UUID tenantId) {
        log.debug("[CustomDispositionService] createForCampaign: campaign={}, code={}, tenant={}",
                campaignId, req.dispositionCode(), tenantId);

        if (customDispositionRepository.existsCodeForCampaign(campaignId, req.dispositionCode(), tenantId)) {
            log.warn("[CustomDispositionService] Duplikat kodu dyspozycji: campaign={}, code={}",
                    campaignId, req.dispositionCode());
            throw new ConflictException("Disposition code already exists for this campaign");
        }

        CustomDisposition d = new CustomDisposition();
        d.setTenantId(tenantId);
        d.setCampaignId(campaignId);
        d.setDispositionCode(req.dispositionCode());
        d.setLabel(req.label());
        d.setTone(req.tone());
        d.setOrdinal(req.ordinal());
        d.setActive(true);

        CustomDisposition saved = customDispositionRepository.insert(d);

        log.info("[CustomDispositionService] Dyspozycja kampanii utworzona: id={}, campaign={}", saved.getId(), campaignId);
        return mapToDto(saved);
    }

    // =========================================================================
    // CRUD per kolejka (supervisor)
    // =========================================================================

    /**
     * Zwraca listę wszystkich dyspozycji (włącznie z nieaktywnymi) dla kolejki.
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @return lista dyspozycji posortowana po ordinal ASC
     */
    public List<CustomDispositionDto> listForQueue(UUID queueId, UUID tenantId) {
        log.debug("[CustomDispositionService] listForQueue: queue={}, tenant={}", queueId, tenantId);

        return customDispositionRepository.findAllByQueueId(queueId, tenantId)
                .stream()
                .map(this::mapToDto)
                .toList();
    }

    /**
     * Tworzy nową dyspozycję przypisaną do kolejki.
     *
     * @param queueId  UUID kolejki
     * @param req      dane nowej dyspozycji
     * @param tenantId UUID tenanta
     * @return DTO nowo utworzonej dyspozycji
     * @throws ConflictException gdy kod dyspozycji już istnieje w tej kolejce (HTTP 409)
     */
    public CustomDispositionDto createForQueue(UUID queueId, CreateCustomDispositionRequest req, UUID tenantId) {
        log.debug("[CustomDispositionService] createForQueue: queue={}, code={}, tenant={}",
                queueId, req.dispositionCode(), tenantId);

        if (customDispositionRepository.existsCodeForQueue(queueId, req.dispositionCode(), tenantId)) {
            log.warn("[CustomDispositionService] Duplikat kodu dyspozycji: queue={}, code={}",
                    queueId, req.dispositionCode());
            throw new ConflictException("Disposition code already exists for this queue");
        }

        CustomDisposition d = new CustomDisposition();
        d.setTenantId(tenantId);
        d.setQueueId(queueId);
        d.setDispositionCode(req.dispositionCode());
        d.setLabel(req.label());
        d.setTone(req.tone());
        d.setOrdinal(req.ordinal());
        d.setActive(true);

        CustomDisposition saved = customDispositionRepository.insert(d);

        log.info("[CustomDispositionService] Dyspozycja kolejki utworzona: id={}, queue={}", saved.getId(), queueId);
        return mapToDto(saved);
    }

    // =========================================================================
    // CRUD wspólne (supervisor)
    // =========================================================================

    /**
     * Aktualizuje istniejącą dyspozycję.
     *
     * <p>Aktualizowalne pola: {@code label}, {@code tone}, {@code ordinal}, {@code isActive}.
     * Kod dyspozycji ({@code dispositionCode}) jest niezmienny.
     *
     * @param dispositionId UUID dyspozycji
     * @param req           dane do aktualizacji
     * @param tenantId      UUID tenanta
     * @return DTO zaktualizowanej dyspozycji
     * @throws ResourceNotFoundException gdy dyspozycja nie istnieje (HTTP 404)
     */
    public CustomDispositionDto update(UUID dispositionId, UpdateCustomDispositionRequest req, UUID tenantId) {
        log.debug("[CustomDispositionService] update: id={}, tenant={}", dispositionId, tenantId);

        CustomDisposition existing = customDispositionRepository.findByIdAndTenantId(dispositionId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Dyspozycja nie istnieje: " + dispositionId));

        existing.setLabel(req.label());
        existing.setTone(req.tone());
        existing.setOrdinal(req.ordinal());
        existing.setActive(req.isActive());

        CustomDisposition updated = customDispositionRepository.update(existing);

        log.info("[CustomDispositionService] Dyspozycja zaktualizowana: id={}", dispositionId);
        return mapToDto(updated);
    }

    /**
     * Usuwa dyspozycję.
     *
     * @param dispositionId UUID dyspozycji
     * @param tenantId      UUID tenanta
     * @throws ResourceNotFoundException gdy dyspozycja nie istnieje (HTTP 404)
     */
    public void delete(UUID dispositionId, UUID tenantId) {
        log.debug("[CustomDispositionService] delete: id={}, tenant={}", dispositionId, tenantId);

        int deleted = customDispositionRepository.delete(dispositionId, tenantId);

        if (deleted == 0) {
            throw new ResourceNotFoundException("Dyspozycja nie istnieje: " + dispositionId);
        }

        log.info("[CustomDispositionService] Dyspozycja usunięta: id={}", dispositionId);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private CustomDispositionDto mapToDto(CustomDisposition d) {
        return new CustomDispositionDto(
                d.getId(),
                d.getDispositionCode(),
                d.getLabel(),
                d.getTone(),
                d.getOrdinal(),
                d.isActive(),
                d.getCreatedAt()
        );
    }

    private AvailableDispositionDto mapToAvailable(CustomDisposition d) {
        return new AvailableDispositionDto(
                d.getDispositionCode(),
                d.getLabel(),
                d.getTone(),
                d.getOrdinal()
        );
    }
}
