package com.contactcenter.domain.queue;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.queue.dto.CreateQueueRequest;
import com.contactcenter.api.queue.dto.QueueResponse;
import com.contactcenter.api.queue.dto.UpdateQueueRequest;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.domain.tenant.TenantResourceLimitService;
import com.contactcenter.infrastructure.aspect.Audited;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class QueueServiceImpl implements QueueService {

    /** Dozwolone wartości strategii routingu (zgodne z ENUM {@code routing_strategy} w DB). */
    private static final List<String> ROUTING_STRATEGIES = List.of(
            "ROUND_ROBIN", "FIRST_AVAILABLE", "SKILL_BASED"
    );

    private final QueueRepository queueRepository;
    private final QueueAssignmentRepository queueAssignmentRepository;
    private final TransferAgentQueueRepository transferAgentQueueRepository;
    private final TransferQueueStatsRepository transferQueueStatsRepository;
    private final TenantResourceLimitService tenantResourceLimitService;

    @Override
    @Transactional
    @Audited(action = "QUEUE_CREATED", entityType = "QUEUE")
    public QueueResponse createQueue(CreateQueueRequest request, UUID tenantId) {
        tenantResourceLimitService.checkQueueLimit(tenantId);

        Queue queue = Queue.builder()
                .queueId(UUID.randomUUID())
                .tenantId(tenantId)
                .name(request.name().trim())
                .routingStrategy(request.routingStrategy())
                .requiredSkills(request.requiredSkills() != null
                        ? new ArrayList<>(request.requiredSkills())
                        : new ArrayList<>())
                .stickyAgentTimeoutSeconds(request.stickyAgentTimeoutSeconds() != null
                        ? request.stickyAgentTimeoutSeconds()
                        : 60)
                .maxConcurrentContactsPerAgent(request.maxConcurrentContactsPerAgent() != null
                        ? request.maxConcurrentContactsPerAgent()
                        : 1)
                .emailAddress(request.emailAddress())
                .waitConfig(request.waitConfig())
                .active(request.active() != null ? request.active() : true)
                .build();

        // Ręczne ustawienie timestamps – @PrePersist nie wywołuje się przy natywnym INSERT
        queue.setCreatedAt(Instant.now());

        Queue saved = queueRepository.insert(queue);
        log.info("[QueueService] Kolejka utworzona: queueId={}, tenantId={}, strategy={}",
                saved.getQueueId(), tenantId, saved.getRoutingStrategy());

        return QueueResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<QueueResponse> listQueues(UUID tenantId, String name, int page, int size) {
        PagedResponse<Queue> page_ = queueRepository.findAllByTenantId(tenantId, name, page, size);
        List<QueueResponse> content = page_.content().stream()
                .map(q -> QueueResponse.from(
                        q,
                        q.isAllAgents() ? -1 : queueAssignmentRepository.countAssignments(q.getQueueId())
                ))
                .toList();
        return new PagedResponse<>(content, page_.page(), page_.size(),
                page_.totalElements(), page_.totalPages(), page_.first(), page_.last());
    }

    @Override
    @Transactional(readOnly = true)
    public QueueResponse getQueue(UUID queueId, UUID tenantId) {
        Queue queue = findQueueOrThrow(queueId, tenantId);
        return QueueResponse.from(queue);
    }

    @Override
    @Transactional
    @Audited(action = "QUEUE_UPDATED", entityType = "QUEUE", captureOldValue = true,
             fetchOldValueMethod = "getQueue", entityIdParamIndex = 0)
    public QueueResponse updateQueue(UUID queueId, UpdateQueueRequest request, UUID tenantId) {
        Queue queue = findQueueOrThrow(queueId, tenantId);

        if (request.name() != null) {
            queue.setName(request.name().trim());
        }
        if (request.routingStrategy() != null) {
            queue.setRoutingStrategy(request.routingStrategy());
        }
        if (request.requiredSkills() != null) {
            queue.setRequiredSkills(new ArrayList<>(request.requiredSkills()));
        }
        if (request.stickyAgentTimeoutSeconds() != null) {
            queue.setStickyAgentTimeoutSeconds(request.stickyAgentTimeoutSeconds());
        }
        if (request.maxConcurrentContactsPerAgent() != null) {
            queue.setMaxConcurrentContactsPerAgent(request.maxConcurrentContactsPerAgent());
        }
        if (request.waitConfig() != null) {
            queue.setWaitConfig(request.waitConfig());
        }
        if (request.active() != null) {
            queue.setActive(request.active());
        }
        if (request.emailAddress() != null) {
            queue.setEmailAddress(request.emailAddress().trim().toLowerCase());
        }

        int updated = queueRepository.update(queue);
        if (updated == 0) {
            throw new EntityNotFoundException("Kolejka nie istnieje: " + queueId);
        }

        log.info("[QueueService] Kolejka zaktualizowana: queueId={}, tenantId={}", queueId, tenantId);

        // Po natywnym UPDATE pobieramy świeże dane z DB
        Queue refreshed = findQueueOrThrow(queueId, tenantId);
        return QueueResponse.from(refreshed);
    }

    @Override
    @Transactional
    @Audited(action = "QUEUE_DELETED", entityType = "QUEUE", captureOldValue = true,
             fetchOldValueMethod = "getQueue", entityIdParamIndex = 0)
    public void deleteQueue(UUID queueId, UUID tenantId) {
        // Weryfikacja istnienia
        findQueueOrThrow(queueId, tenantId);

        // Sprawdź aktywne kontakty
        boolean hasActiveContacts = queueRepository.hasActiveContacts(queueId, tenantId);
        if (hasActiveContacts) {
            throw new InvalidOperationException(
                    "Nie można usunąć kolejki z aktywnymi kontaktami. " +
                    "Poczekaj na zakończenie obsługi lub przekieruj kontakty.");
        }

        int updated = queueRepository.softDelete(queueId, tenantId);
        if (updated == 0) {
            throw new EntityNotFoundException("Kolejka nie istnieje: " + queueId);
        }

        log.info("[QueueService] Kolejka deaktywowana: queueId={}, tenantId={}", queueId, tenantId);
    }

    @Override
    public List<String> listRoutingStrategies() {
        return ROUTING_STRATEGIES;
    }

    // =========================================================================
    // Metody delegujące (encapsulation pass – pkt 9 wzorca)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public Optional<Queue> findQueueEntity(UUID queueId, UUID tenantId) {
        return queueRepository.findByIdAndTenantId(queueId, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Queue> getAllQueues(UUID tenantId) {
        return queueRepository.findAllByTenantId(tenantId, null, 0, 1000).content();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Queue> findQueueByEmailAddress(String emailAddress, UUID tenantId) {
        return queueRepository.findByEmailAddressAndTenantId(emailAddress, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Object[]> findActiveQueuesForTransfer(UUID tenantId) {
        return transferQueueStatsRepository.findActiveQueues(tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> countWaitingContactsByQueueIds(UUID tenantId, List<UUID> queueIds) {
        return transferQueueStatsRepository.countWaitingContactsByQueueIds(tenantId, queueIds);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> countAvailableAgentsByQueueIds(UUID tenantId, List<UUID> queueIds) {
        return transferQueueStatsRepository.countAvailableAgentsByQueueIds(tenantId, queueIds);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<String>> findQueueNamesByAgentIds(UUID tenantId, List<UUID> agentIds) {
        return transferAgentQueueRepository.findQueueNamesByAgentIds(tenantId, agentIds);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private Queue findQueueOrThrow(UUID queueId, UUID tenantId) {
        return queueRepository.findByIdAndTenantId(queueId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Kolejka nie istnieje lub nie należy do tego tenanta: " + queueId));
    }
}
