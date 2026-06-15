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

@Slf4j
@Service
@RequiredArgsConstructor
class IvrServiceImpl implements IvrService {

    private final IvrTreeRepository ivrTreeRepository;

    /**
     * PhoneRoutingRuleService – wstrzykiwany przez setter z {@code @Lazy} aby uniknąć
     * cyklicznej zależności: PhoneRoutingRuleServiceImpl → IvrServiceImpl → PhoneRoutingRuleService.
     */
    private PhoneRoutingRuleService phoneRoutingRuleService;

    @Autowired
    @Lazy
    public void setPhoneRoutingRuleService(PhoneRoutingRuleService phoneRoutingRuleService) {
        this.phoneRoutingRuleService = phoneRoutingRuleService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<IvrResponse> listIvrTrees(UUID tenantId) {
        return ivrTreeRepository.findAllByTenantId(tenantId)
                .stream()
                .map(IvrResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public IvrResponse getIvrTree(UUID ivrId, UUID tenantId) {
        IvrTree ivr = findOrThrow(ivrId, tenantId);
        return IvrResponse.from(ivr);
    }

    @Override
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

    @Override
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

    @Override
    @Transactional
    public void deleteIvrTree(UUID ivrId, UUID tenantId) {
        findOrThrow(ivrId, tenantId);

        int deleted = ivrTreeRepository.delete(ivrId, tenantId);
        if (deleted == 0) {
            throw new EntityNotFoundException("Drzewo IVR nie istnieje: " + ivrId);
        }

        log.info("[IvrService] Drzewo IVR usunięte: ivrId={}, tenantId={}", ivrId, tenantId);
    }

    @Override
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

    @Override
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

    @Override
    @Transactional(readOnly = true)
    public boolean existsActiveIvrTree(UUID tenantId, UUID ivrId) {
        return ivrTreeRepository.existsActiveByIvrId(ivrId, tenantId);
    }

    private IvrTree findOrThrow(UUID ivrId, UUID tenantId) {
        return ivrTreeRepository.findByIvrIdAndTenantId(ivrId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Drzewo IVR nie istnieje lub nie należy do tego tenanta: " + ivrId));
    }
}
