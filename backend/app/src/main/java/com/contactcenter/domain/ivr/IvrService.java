package com.contactcenter.domain.ivr;

import com.contactcenter.api.ivr.dto.CreateIvrRequest;
import com.contactcenter.api.ivr.dto.IvrResponse;
import com.contactcenter.api.ivr.dto.UpdateIvrRequest;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.phonenumber.PhoneRoutingRuleService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Serwis domenowy zarządzający drzewami IVR (CRUD).
 *
 * <p>Dostępny dla ról SUPERVISOR i ADMIN. Zapewnia:
 * <ul>
 *   <li>Tworzenie i aktualizację drzew IVR</li>
 *   <li>Aktywację i deaktywację drzew IVR (wiele drzew może być aktywnych jednocześnie)</li>
 *   <li>Usunięcie drzewa IVR</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IvrService {

    private final IvrTreeRepository ivrTreeRepository;

    /**
     * PhoneRoutingRuleService – wstrzykiwany przez setter z {@code @Lazy} aby uniknąć
     * cyklicznej zależności: PhoneRoutingRuleServiceImpl → IvrService → PhoneRoutingRuleService.
     */
    private PhoneRoutingRuleService phoneRoutingRuleService;

    @Autowired
    @Lazy
    public void setPhoneRoutingRuleService(PhoneRoutingRuleService phoneRoutingRuleService) {
        this.phoneRoutingRuleService = phoneRoutingRuleService;
    }

    // =========================================================================
    // Lista
    // =========================================================================

    /**
     * Zwraca listę wszystkich drzew IVR dla tenanta.
     *
     * @param tenantId UUID tenanta
     * @return lista DTO drzew IVR
     */
    @Transactional(readOnly = true)
    public List<IvrResponse> listIvrTrees(UUID tenantId) {
        return ivrTreeRepository.findAllByTenantId(tenantId)
                .stream()
                .map(IvrResponse::from)
                .toList();
    }

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera drzewo IVR po identyfikatorze.
     *
     * @param ivrId    UUID drzewa IVR
     * @param tenantId UUID tenanta
     * @return DTO drzewa IVR
     * @throws EntityNotFoundException gdy drzewo nie istnieje
     */
    @Transactional(readOnly = true)
    public IvrResponse getIvrTree(UUID ivrId, UUID tenantId) {
        IvrTree ivr = findOrThrow(ivrId, tenantId);
        return IvrResponse.from(ivr);
    }

    // =========================================================================
    // Tworzenie
    // =========================================================================

    /**
     * Tworzy nowe drzewo IVR.
     *
     * @param request  dane nowego drzewa IVR
     * @param tenantId UUID tenanta
     * @param userId   UUID użytkownika tworzącego
     * @return DTO nowo utworzonego drzewa IVR
     */
    @Transactional
    public IvrResponse createIvrTree(CreateIvrRequest request, UUID tenantId, UUID userId) {
        IvrTree ivr = IvrTree.builder()
                .ivrId(UUID.randomUUID())
                .tenantId(tenantId)
                .name(request.name().trim())
                .definition(request.definition())
                .version(1)
                .active(false)
                .createdBy(userId)
                .createdAt(Instant.now())
                .build();

        IvrTree saved = ivrTreeRepository.insert(ivr);
        log.info("[IvrService] Drzewo IVR utworzone: ivrId={}, tenantId={}", saved.getIvrId(), tenantId);
        return IvrResponse.from(saved);
    }

    // =========================================================================
    // Aktualizacja
    // =========================================================================

    /**
     * Aktualizuje drzewo IVR (PATCH semantics – pola null ignorowane).
     *
     * @param ivrId    UUID drzewa IVR
     * @param request  dane do aktualizacji
     * @param tenantId UUID tenanta
     * @return DTO zaktualizowanego drzewa IVR
     * @throws EntityNotFoundException gdy drzewo nie istnieje
     */
    @Transactional
    public IvrResponse updateIvrTree(UUID ivrId, UpdateIvrRequest request, UUID tenantId) {
        IvrTree ivr = findOrThrow(ivrId, tenantId);

        if (request.name() != null) {
            ivr.setName(request.name().trim());
        }
        if (request.definition() != null) {
            ivr.setDefinition(request.definition());
        }

        int updated = ivrTreeRepository.update(ivr);
        if (updated == 0) {
            throw new EntityNotFoundException("Drzewo IVR nie istnieje: " + ivrId);
        }

        log.info("[IvrService] Drzewo IVR zaktualizowane: ivrId={}, tenantId={}", ivrId, tenantId);

        // Odśwież z bazy (trigger mógł zmienić version)
        IvrTree refreshed = findOrThrow(ivrId, tenantId);
        return IvrResponse.from(refreshed);
    }

    // =========================================================================
    // Usunięcie
    // =========================================================================

    /**
     * Usuwa drzewo IVR (fizyczne usunięcie – tabela nie ma is_deleted).
     *
     * @param ivrId    UUID drzewa IVR
     * @param tenantId UUID tenanta
     * @throws EntityNotFoundException gdy drzewo nie istnieje
     */
    @Transactional
    public void deleteIvrTree(UUID ivrId, UUID tenantId) {
        findOrThrow(ivrId, tenantId);

        int deleted = ivrTreeRepository.delete(ivrId, tenantId);
        if (deleted == 0) {
            throw new EntityNotFoundException("Drzewo IVR nie istnieje: " + ivrId);
        }

        log.info("[IvrService] Drzewo IVR usunięte: ivrId={}, tenantId={}", ivrId, tenantId);
    }

    // =========================================================================
    // Aktywacja
    // =========================================================================

    /**
     * Aktywuje drzewo IVR.
     *
     * <p>Wiele drzew IVR może być aktywnych jednocześnie per tenant –
     * które drzewo obsługuje ruch przychodzący decydują reguły {@code phone_routing_rule}.
     *
     * @param ivrId    UUID drzewa IVR do aktywacji
     * @param tenantId UUID tenanta
     * @return DTO aktywowanego drzewa IVR
     * @throws EntityNotFoundException gdy drzewo nie istnieje
     */
    @Transactional
    public IvrResponse activateIvrTree(UUID ivrId, UUID tenantId) {
        IvrTree ivr = findOrThrow(ivrId, tenantId);

        ivr.setActive(true);
        int updated = ivrTreeRepository.update(ivr);
        if (updated == 0) {
            throw new EntityNotFoundException("Drzewo IVR nie istnieje: " + ivrId);
        }

        log.info("[IvrService] Drzewo IVR aktywowane: ivrId={}, tenantId={}", ivrId, tenantId);

        IvrTree refreshed = findOrThrow(ivrId, tenantId);
        return IvrResponse.from(refreshed);
    }

    // =========================================================================
    // Deaktywacja
    // =========================================================================

    /**
     * Deaktywuje drzewo IVR.
     *
     * <p>Blokuje deaktywację gdy drzewo jest przypisane do co najmniej jednej reguły routingu.
     * Przed deaktywacją należy usunąć drzewo ze wszystkich reguł {@code phone_routing_rule}.
     *
     * @param ivrId    UUID drzewa IVR do deaktywacji
     * @param tenantId UUID tenanta
     * @return DTO deaktywowanego drzewa IVR
     * @throws EntityNotFoundException gdy drzewo nie istnieje
     * @throws ConflictException       gdy drzewo jest przypisane do reguły routingu (HTTP 409)
     */
    @Transactional
    public IvrResponse deactivateIvrTree(UUID ivrId, UUID tenantId) {
        IvrTree ivr = findOrThrow(ivrId, tenantId);

        if (phoneRoutingRuleService.existsRulesByIvrTreeId(tenantId, ivrId)) {
            throw new ConflictException(
                    "Nie można deaktywować drzewa IVR przypisanego do reguły routingu. "
                    + "Usuń drzewo z reguł routingu przed deaktywacją.");
        }

        ivr.setActive(false);
        int updated = ivrTreeRepository.update(ivr);
        if (updated == 0) {
            throw new EntityNotFoundException("Drzewo IVR nie istnieje: " + ivrId);
        }

        log.info("[IvrService] Drzewo IVR deaktywowane: ivrId={}, tenantId={}", ivrId, tenantId);

        IvrTree refreshed = findOrThrow(ivrId, tenantId);
        return IvrResponse.from(refreshed);
    }

    // =========================================================================
    // Cross-domain
    // =========================================================================

    /**
     * Sprawdza czy drzewo IVR o podanym identyfikatorze istnieje i jest aktywne.
     *
     * <p>Używane przez {@code domain.phonenumber.PhoneRoutingRuleService} do walidacji
     * {@code ivrTreeId} przy tworzeniu/aktualizacji reguły routingu – reguła może wskazywać
     * tylko na aktywne drzewo IVR.
     *
     * @param tenantId UUID tenanta
     * @param ivrId    UUID drzewa IVR
     * @return {@code true} gdy drzewo istnieje i ma {@code is_active = true}
     */
    @Transactional(readOnly = true)
    public boolean existsActiveIvrTree(UUID tenantId, UUID ivrId) {
        return ivrTreeRepository.existsActiveByIvrId(ivrId, tenantId);
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    private IvrTree findOrThrow(UUID ivrId, UUID tenantId) {
        return ivrTreeRepository.findByIvrIdAndTenantId(ivrId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Drzewo IVR nie istnieje lub nie należy do tego tenanta: " + ivrId));
    }
}
