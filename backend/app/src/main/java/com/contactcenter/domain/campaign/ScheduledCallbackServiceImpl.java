package com.contactcenter.domain.campaign;

import com.contactcenter.domain.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementacja {@link ScheduledCallbackService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class ScheduledCallbackServiceImpl implements ScheduledCallbackService {

    private final ScheduledCallbackRepository scheduledCallbackRepository;

    @Transactional
    @Override
    public ScheduledCallback saveCallback(ScheduledCallback callback) {
        return scheduledCallbackRepository.save(callback);
    }

    @Transactional(readOnly = true)
    @Override
    public Optional<ScheduledCallback> findCallbackEntity(UUID callbackId, UUID tenantId) {
        return scheduledCallbackRepository.findById(callbackId, tenantId);
    }

    @Transactional(readOnly = true)
    @Override
    public ScheduledCallback getCallbackOrThrow(UUID callbackId, UUID tenantId) {
        return scheduledCallbackRepository.findById(callbackId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Callback nie istnieje: " + callbackId));
    }

    @Transactional(readOnly = true)
    @Override
    public List<ScheduledCallback> findCallbacksByOriginContactId(UUID originContactId, UUID tenantId) {
        return scheduledCallbackRepository.findByOriginContactId(originContactId, tenantId);
    }

    @Transactional(readOnly = true)
    @Override
    public List<ScheduledCallback> getAgentCalendarCallbacks(UUID tenantId, UUID agentId, Instant from, Instant to) {
        return scheduledCallbackRepository.findByAgentIdAndScheduledAtBetween(tenantId, agentId, from, to);
    }

    @Transactional(readOnly = true)
    @Override
    public List<ScheduledCallback> findCallbacksByAgentId(UUID tenantId, UUID agentId, String status,
                                                            String sortDirection, int page, int size) {
        return scheduledCallbackRepository.findByAgentId(tenantId, agentId, status, sortDirection, page, size);
    }

    @Transactional(readOnly = true)
    @Override
    public long countCallbacksByAgentId(UUID tenantId, UUID agentId, String status) {
        return scheduledCallbackRepository.countByAgentId(tenantId, agentId, status);
    }

    @Transactional(readOnly = true)
    @Override
    public List<ScheduledCallback> findCallbacksByTenantIdWithFilters(UUID tenantId, String status, UUID agentIdFilter,
                                                                        String sortDirection, int page, int size) {
        return scheduledCallbackRepository.findByTenantIdWithFilters(tenantId, status, agentIdFilter, sortDirection, page, size);
    }

    @Transactional(readOnly = true)
    @Override
    public long countCallbacksByTenantIdWithFilters(UUID tenantId, String status, UUID agentIdFilter) {
        return scheduledCallbackRepository.countByTenantIdWithFilters(tenantId, status, agentIdFilter);
    }

    @Transactional
    @Override
    public int cancelCallback(UUID callbackId, UUID tenantId) {
        log.info("[ScheduledCallbackService] Anulowanie callbacku (soft-delete): callbackId={}, tenant={}", callbackId, tenantId);
        return scheduledCallbackRepository.cancelCallback(callbackId, tenantId);
    }
}
