package com.contactcenter.domain.service;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.queue.dto.CreateQueueRequest;
import com.contactcenter.api.queue.dto.QueueResponse;
import com.contactcenter.api.queue.dto.UpdateQueueRequest;
import com.contactcenter.domain.exception.InvalidOperationException;
import com.contactcenter.domain.model.Queue;
import com.contactcenter.domain.tenant.TenantResourceLimitService;
import com.contactcenter.domain.repository.QueueAssignmentRepository;
import com.contactcenter.domain.repository.QueueRepository;
import com.contactcenter.infrastructure.aspect.Audited;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Serwis domenowy zarządzający kolejkami kontaktów.
 *
 * <p>Implementuje BE-020: Queue API – CRUD kolejek i konfiguracja routingu.
 *
 * <p>Bezpieczeństwo:
 * <ul>
 *   <li>Każdy odczyt i zapis filtruje po tenantId z TenantContext</li>
 *   <li>Przed utworzeniem kolejki sprawdzany jest limit zasobów tenanta</li>
 *   <li>Usunięcie (deaktywacja) blokowane gdy kolejka ma aktywne kontakty</li>
 *   <li>Operacje modyfikujące logują zdarzenia audytowe przez {@link Audited}</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueService {

    /** Dozwolone wartości strategii routingu (zgodne z ENUM {@code routing_strategy} w DB). */
    private static final List<String> ROUTING_STRATEGIES = List.of(
            "ROUND_ROBIN", "FIRST_AVAILABLE", "SKILL_BASED"
    );

    private final QueueRepository queueRepository;
    private final QueueAssignmentRepository queueAssignmentRepository;
    private final TenantResourceLimitService tenantResourceLimitService;

    // =========================================================================
    // Tworzenie kolejki
    // =========================================================================

    /**
     * Tworzy nową kolejkę w tenancie.
     *
     * <p>Przepływ:
     * <ol>
     *   <li>Sprawdza limit kolejek przez {@link TenantResourceLimitService}</li>
     *   <li>Buduje encję z wartościami domyślnymi dla pól opcjonalnych</li>
     *   <li>Zapisuje przez natywny INSERT</li>
     * </ol>
     *
     * @param request  dane nowej kolejki
     * @param tenantId UUID tenanta z TenantContext
     * @return DTO nowo utworzonej kolejki
     * @throws com.contactcenter.domain.exception.ResourceLimitExceededException HTTP 422 gdy limit przekroczony
     */
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

    // =========================================================================
    // Lista kolejek
    // =========================================================================

    /**
     * Zwraca paginowaną listę kolejek tenanta.
     *
     * @param tenantId UUID tenanta
     * @param name     opcjonalny filtr nazwy (ILIKE)
     * @param page     numer strony (0-based)
     * @param size     rozmiar strony
     * @return strona DTO kolejek
     */
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

    // =========================================================================
    // Odczyt pojedynczej kolejki
    // =========================================================================

    /**
     * Pobiera kolejkę po ID.
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @return DTO kolejki
     * @throws EntityNotFoundException HTTP 422 gdy kolejka nie istnieje lub nie należy do tenanta
     */
    @Transactional(readOnly = true)
    public QueueResponse getQueue(UUID queueId, UUID tenantId) {
        Queue queue = findQueueOrThrow(queueId, tenantId);
        return QueueResponse.from(queue);
    }

    // =========================================================================
    // Aktualizacja kolejki
    // =========================================================================

    /**
     * Aktualizuje kolejkę (PATCH semantics).
     *
     * <p>Pola null w żądaniu są ignorowane – wartości pozostają bez zmian.
     *
     * @param queueId  UUID kolejki
     * @param request  dane do aktualizacji
     * @param tenantId UUID tenanta
     * @return DTO zaktualizowanej kolejki
     * @throws EntityNotFoundException HTTP 422 gdy kolejka nie istnieje
     */
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

    // =========================================================================
    // Usunięcie (deaktywacja) kolejki
    // =========================================================================

    /**
     * Deaktywuje kolejkę (odpowiednik soft-delete dla tabel bez is_deleted).
     *
     * <p>Reguły:
     * <ul>
     *   <li>Nie można deaktywować kolejki z aktywnymi kontaktami (QUEUED/ACTIVE/ON_HOLD)</li>
     * </ul>
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @throws InvalidOperationException HTTP 409 gdy kolejka ma aktywne kontakty
     * @throws EntityNotFoundException   HTTP 422 gdy kolejka nie istnieje
     */
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

    // =========================================================================
    // Strategie routingu
    // =========================================================================

    /**
     * Zwraca listę dostępnych strategii routingu.
     *
     * <p>Wartości zgodne z ENUM {@code routing_strategy} w PostgreSQL.
     *
     * @return lista nazw strategii routingu
     */
    public List<String> listRoutingStrategies() {
        return ROUTING_STRATEGIES;
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
