package com.contactcenter.domain.disposition;

import com.contactcenter.domain.disposition.dto.ApplySetResponse;
import com.contactcenter.domain.disposition.dto.CreateDispositionSetItemRequest;
import com.contactcenter.domain.disposition.dto.CreateDispositionSetRequest;
import com.contactcenter.domain.disposition.dto.DispositionSetDetailDto;
import com.contactcenter.domain.disposition.dto.DispositionSetDto;
import com.contactcenter.domain.disposition.dto.DispositionSetItemDto;
import com.contactcenter.domain.disposition.dto.UpdateDispositionSetItemRequest;
import com.contactcenter.domain.disposition.dto.UpdateDispositionSetRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Serwis zarządzający zestawami dyspozycji wielokrotnego użytku (BE-095).
 *
 * <p>Odpowiada za:
 * <ul>
 *   <li>CRUD zestawów ({@link DispositionSet}) i ich elementów ({@link DispositionSetItem})</li>
 *   <li>Aplikowanie zestawu do kampanii lub kolejki przez kopiowanie elementów
 *       jako {@link CustomDisposition}</li>
 * </ul>
 *
 * <p>Izolacja multi-tenant przez przekazanie {@code tenantId} do każdej metody repozytorium.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DispositionSetService {

    private final DispositionSetRepository setRepo;
    private final DispositionSetItemRepository itemRepo;
    private final CustomDispositionRepository customDispositionRepository;

    // =========================================================================
    // CRUD zestawów
    // =========================================================================

    /**
     * Zwraca listę wszystkich zestawów dyspozycji tenanta z liczbą elementów.
     *
     * @param tenantId UUID tenanta
     * @return lista zestawów posortowana po name ASC
     */
    public List<DispositionSetDto> listSets(UUID tenantId) {
        log.debug("[DispositionSetService] listSets: tenant={}", tenantId);

        return setRepo.findAllByTenantId(tenantId).stream()
                .map(s -> new DispositionSetDto(
                        s.getId(),
                        s.getName(),
                        s.getDescription(),
                        itemRepo.countBySetId(s.getId(), tenantId),
                        s.getCreatedAt()))
                .toList();
    }

    /**
     * Zwraca szczegóły zestawu dyspozycji wraz z listą elementów.
     *
     * @param setId    UUID zestawu
     * @param tenantId UUID tenanta
     * @return DTO szczegółów zestawu
     * @throws ResourceNotFoundException gdy zestaw nie istnieje (HTTP 404)
     */
    public DispositionSetDetailDto getSet(UUID setId, UUID tenantId) {
        DispositionSet s = setRepo.findByIdAndTenantId(setId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Zestaw nie istnieje: " + setId));

        List<DispositionSetItemDto> items = itemRepo.findBySetId(setId, tenantId)
                .stream().map(this::mapItemToDto).toList();

        return new DispositionSetDetailDto(s.getId(), s.getName(), s.getDescription(), items, s.getCreatedAt());
    }

    /**
     * Tworzy nowy zestaw dyspozycji.
     *
     * @param req      dane nowego zestawu
     * @param tenantId UUID tenanta
     * @return DTO nowo utworzonego zestawu
     * @throws ConflictException gdy zestaw o tej nazwie już istnieje (HTTP 409)
     */
    public DispositionSetDto createSet(CreateDispositionSetRequest req, UUID tenantId) {
        log.debug("[DispositionSetService] createSet: name={}, tenant={}", req.name(), tenantId);

        if (setRepo.existsByNameAndTenantId(req.name(), tenantId)) {
            throw new ConflictException("Zestaw o nazwie '" + req.name() + "' już istnieje");
        }

        DispositionSet s = new DispositionSet();
        s.setTenantId(tenantId);
        s.setName(req.name());
        s.setDescription(req.description());

        DispositionSet saved = setRepo.insert(s);

        log.info("[DispositionSetService] Zestaw utworzony: id={}, tenant={}", saved.getId(), tenantId);
        return new DispositionSetDto(saved.getId(), saved.getName(), saved.getDescription(), 0, saved.getCreatedAt());
    }

    /**
     * Aktualizuje istniejący zestaw dyspozycji.
     *
     * @param setId    UUID zestawu
     * @param req      dane do aktualizacji
     * @param tenantId UUID tenanta
     * @return DTO zaktualizowanego zestawu
     * @throws ResourceNotFoundException gdy zestaw nie istnieje (HTTP 404)
     * @throws ConflictException         gdy nowa nazwa jest już zajęta (HTTP 409)
     */
    public DispositionSetDto updateSet(UUID setId, UpdateDispositionSetRequest req, UUID tenantId) {
        log.debug("[DispositionSetService] updateSet: setId={}, name={}, tenant={}", setId, req.name(), tenantId);

        DispositionSet existing = setRepo.findByIdAndTenantId(setId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Zestaw nie istnieje: " + setId));

        if (!existing.getName().equals(req.name()) && setRepo.existsByNameAndTenantId(req.name(), tenantId)) {
            throw new ConflictException("Zestaw o nazwie '" + req.name() + "' już istnieje");
        }

        existing.setName(req.name());
        existing.setDescription(req.description());

        return setRepo.update(existing)
                .map(s -> new DispositionSetDto(
                        s.getId(),
                        s.getName(),
                        s.getDescription(),
                        itemRepo.countBySetId(s.getId(), tenantId),
                        s.getCreatedAt()))
                .orElseThrow(() -> new ResourceNotFoundException("Zestaw nie istnieje: " + setId));
    }

    /**
     * Usuwa zestaw dyspozycji wraz z jego elementami (CASCADE w bazie).
     *
     * @param setId    UUID zestawu
     * @param tenantId UUID tenanta
     * @throws ResourceNotFoundException gdy zestaw nie istnieje (HTTP 404)
     */
    public void deleteSet(UUID setId, UUID tenantId) {
        log.debug("[DispositionSetService] deleteSet: setId={}, tenant={}", setId, tenantId);

        if (setRepo.delete(setId, tenantId) == 0) {
            throw new ResourceNotFoundException("Zestaw nie istnieje: " + setId);
        }

        log.info("[DispositionSetService] Zestaw usunięty: id={}, tenant={}", setId, tenantId);
    }

    // =========================================================================
    // CRUD elementów zestawu
    // =========================================================================

    /**
     * Zwraca listę elementów zestawu posortowaną po ordinal ASC.
     *
     * @param setId    UUID zestawu
     * @param tenantId UUID tenanta
     * @return lista elementów
     * @throws ResourceNotFoundException gdy zestaw nie istnieje (HTTP 404)
     */
    public List<DispositionSetItemDto> listItems(UUID setId, UUID tenantId) {
        setRepo.findByIdAndTenantId(setId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Zestaw nie istnieje: " + setId));

        return itemRepo.findBySetId(setId, tenantId).stream().map(this::mapItemToDto).toList();
    }

    /**
     * Dodaje nowy element do zestawu dyspozycji.
     *
     * @param setId    UUID zestawu
     * @param req      dane nowego elementu
     * @param tenantId UUID tenanta
     * @return DTO nowo utworzonego elementu
     * @throws ResourceNotFoundException gdy zestaw nie istnieje (HTTP 404)
     * @throws ConflictException         gdy kod dyspozycji już istnieje w zestawie (HTTP 409)
     */
    public DispositionSetItemDto addItem(UUID setId, CreateDispositionSetItemRequest req, UUID tenantId) {
        log.debug("[DispositionSetService] addItem: setId={}, code={}, tenant={}", setId, req.dispositionCode(), tenantId);

        setRepo.findByIdAndTenantId(setId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Zestaw nie istnieje: " + setId));

        if (itemRepo.existsByCodeAndSetId(req.dispositionCode(), setId, tenantId)) {
            throw new ConflictException("Kod '" + req.dispositionCode() + "' już istnieje w tym zestawie");
        }

        DispositionSetItem item = new DispositionSetItem();
        item.setSetId(setId);
        item.setTenantId(tenantId);
        item.setDispositionCode(req.dispositionCode());
        item.setLabel(req.label());
        item.setTone(req.tone());
        item.setOrdinal(req.ordinal());

        DispositionSetItemDto dto = mapItemToDto(itemRepo.insert(item));
        log.info("[DispositionSetService] Element dodany: id={}, setId={}", dto.id(), setId);
        return dto;
    }

    /**
     * Aktualizuje element zestawu dyspozycji.
     *
     * @param setId    UUID zestawu
     * @param itemId   UUID elementu
     * @param req      dane do aktualizacji
     * @param tenantId UUID tenanta
     * @return DTO zaktualizowanego elementu
     * @throws ResourceNotFoundException gdy element nie istnieje (HTTP 404)
     */
    public DispositionSetItemDto updateItem(UUID setId, UUID itemId, UpdateDispositionSetItemRequest req, UUID tenantId) {
        log.debug("[DispositionSetService] updateItem: setId={}, itemId={}, tenant={}", setId, itemId, tenantId);

        DispositionSetItem item = itemRepo.findByIdAndSetId(itemId, setId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Element nie istnieje: " + itemId));

        item.setLabel(req.label());
        item.setTone(req.tone());
        item.setOrdinal(req.ordinal());

        return itemRepo.update(item)
                .map(this::mapItemToDto)
                .orElseThrow(() -> new ResourceNotFoundException("Element nie istnieje: " + itemId));
    }

    /**
     * Usuwa element z zestawu dyspozycji.
     *
     * @param setId    UUID zestawu
     * @param itemId   UUID elementu
     * @param tenantId UUID tenanta
     * @throws ResourceNotFoundException gdy element nie istnieje (HTTP 404)
     */
    public void removeItem(UUID setId, UUID itemId, UUID tenantId) {
        log.debug("[DispositionSetService] removeItem: setId={}, itemId={}, tenant={}", setId, itemId, tenantId);

        if (itemRepo.delete(itemId, setId, tenantId) == 0) {
            throw new ResourceNotFoundException("Element nie istnieje: " + itemId);
        }

        log.info("[DispositionSetService] Element usunięty: id={}, setId={}", itemId, setId);
    }

    // =========================================================================
    // Aplikowanie zestawu
    // =========================================================================

    /**
     * Kopiuje wszystkie elementy zestawu jako dyspozycje przypisane do kampanii.
     *
     * <p>Operacja best-effort: duplikaty kodów są pomijane i zliczane w {@code skipped}.
     * Umożliwia to wielokrotne wywołanie bez ryzyka utraty istniejących dyspozycji.
     *
     * @param setId      UUID zestawu do zastosowania
     * @param campaignId UUID kampanii docelowej
     * @param tenantId   UUID tenanta
     * @return podsumowanie operacji z liczbą skopiowanych i pominiętych elementów
     * @throws ResourceNotFoundException gdy zestaw nie istnieje lub jest pusty (HTTP 404)
     */
    @Transactional
    public ApplySetResponse applyToCampaign(UUID setId, UUID campaignId, UUID tenantId) {
        log.info("[DispositionSetService] applyToCampaign: setId={}, campaignId={}, tenant={}", setId, campaignId, tenantId);

        List<DispositionSetItem> items = itemRepo.findBySetId(setId, tenantId);
        if (items.isEmpty()) {
            throw new ResourceNotFoundException("Zestaw nie istnieje lub jest pusty: " + setId);
        }

        int copied = 0, skipped = 0;
        for (DispositionSetItem item : items) {
            try {
                CustomDisposition cd = buildCustomDisposition(item, tenantId);
                cd.setCampaignId(campaignId);
                customDispositionRepository.insert(cd);
                copied++;
            } catch (Exception e) {
                log.warn("[DispositionSetService] Pominięto duplikat kodu '{}' dla kampanii {}: {}",
                        item.getDispositionCode(), campaignId, e.getMessage());
                skipped++;
            }
        }

        String message = buildResultMessage(copied, skipped);
        log.info("[DispositionSetService] applyToCampaign zakończono: {}", message);
        return new ApplySetResponse(copied, skipped, message);
    }

    /**
     * Kopiuje wszystkie elementy zestawu jako dyspozycje przypisane do kolejki.
     *
     * <p>Operacja best-effort: duplikaty kodów są pomijane i zliczane w {@code skipped}.
     * Umożliwia to wielokrotne wywołanie bez ryzyka utraty istniejących dyspozycji.
     *
     * @param setId    UUID zestawu do zastosowania
     * @param queueId  UUID kolejki docelowej
     * @param tenantId UUID tenanta
     * @return podsumowanie operacji z liczbą skopiowanych i pominiętych elementów
     * @throws ResourceNotFoundException gdy zestaw nie istnieje lub jest pusty (HTTP 404)
     */
    @Transactional
    public ApplySetResponse applyToQueue(UUID setId, UUID queueId, UUID tenantId) {
        log.info("[DispositionSetService] applyToQueue: setId={}, queueId={}, tenant={}", setId, queueId, tenantId);

        List<DispositionSetItem> items = itemRepo.findBySetId(setId, tenantId);
        if (items.isEmpty()) {
            throw new ResourceNotFoundException("Zestaw nie istnieje lub jest pusty: " + setId);
        }

        int copied = 0, skipped = 0;
        for (DispositionSetItem item : items) {
            try {
                CustomDisposition cd = buildCustomDisposition(item, tenantId);
                cd.setQueueId(queueId);
                customDispositionRepository.insert(cd);
                copied++;
            } catch (Exception e) {
                log.warn("[DispositionSetService] Pominięto duplikat kodu '{}' dla kolejki {}: {}",
                        item.getDispositionCode(), queueId, e.getMessage());
                skipped++;
            }
        }

        String message = buildResultMessage(copied, skipped);
        log.info("[DispositionSetService] applyToQueue zakończono: {}", message);
        return new ApplySetResponse(copied, skipped, message);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private DispositionSetItemDto mapItemToDto(DispositionSetItem i) {
        return new DispositionSetItemDto(
                i.getId(),
                i.getDispositionCode(),
                i.getLabel(),
                i.getTone(),
                i.getOrdinal());
    }

    private CustomDisposition buildCustomDisposition(DispositionSetItem item, UUID tenantId) {
        CustomDisposition cd = new CustomDisposition();
        cd.setTenantId(tenantId);
        cd.setDispositionCode(item.getDispositionCode());
        cd.setLabel(item.getLabel());
        cd.setTone(item.getTone());
        cd.setOrdinal(item.getOrdinal());
        cd.setActive(true);
        return cd;
    }

    private String buildResultMessage(int copied, int skipped) {
        return "Skopiowano " + copied + " dyspozycji" +
                (skipped > 0 ? " (" + skipped + " pominiętych — duplikat kodu)" : "");
    }
}
