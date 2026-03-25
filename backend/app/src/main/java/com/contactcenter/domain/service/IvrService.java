package com.contactcenter.domain.service;

import com.contactcenter.api.ivr.dto.CreateIvrRequest;
import com.contactcenter.api.ivr.dto.IvrResponse;
import com.contactcenter.api.ivr.dto.UpdateIvrRequest;
import com.contactcenter.domain.model.IvrTree;
import com.contactcenter.domain.repository.IvrTreeRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 *   <li>Aktywację drzewa (dezaktywuje inne per tenant)</li>
 *   <li>Usunięcie drzewa IVR</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IvrService {

    private final IvrTreeRepository ivrTreeRepository;

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
     * Aktywuje drzewo IVR – dezaktywuje wszystkie inne dla tenanta.
     *
     * <p>Zapewnia że tylko jedno drzewo IVR jest aktywne per tenant jednocześnie.
     *
     * @param ivrId    UUID drzewa IVR do aktywacji
     * @param tenantId UUID tenanta
     * @return DTO aktywowanego drzewa IVR
     * @throws EntityNotFoundException gdy drzewo nie istnieje
     */
    @Transactional
    public IvrResponse activateIvrTree(UUID ivrId, UUID tenantId) {
        IvrTree ivr = findOrThrow(ivrId, tenantId);

        // Dezaktywuj wszystkie inne drzewa
        ivrTreeRepository.deactivateAll(tenantId);

        // Aktywuj wybrane drzewo
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
    // Pomocnicze
    // =========================================================================

    private IvrTree findOrThrow(UUID ivrId, UUID tenantId) {
        return ivrTreeRepository.findByIvrIdAndTenantId(ivrId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Drzewo IVR nie istnieje lub nie należy do tego tenanta: " + ivrId));
    }
}
