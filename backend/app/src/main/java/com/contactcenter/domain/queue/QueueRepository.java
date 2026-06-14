package com.contactcenter.domain.queue;

import com.contactcenter.domain.repository.TenantAwareRepository;

import com.contactcenter.api.PagedResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium kolejek (CRUD dla tabeli {@code queue}).
 *
 * <p>Tabela {@code queue} nie jest partycjonowana – INSERT/UPDATE/DELETE przez
 * {@link jakarta.persistence.EntityManager} z natywnym SQL (dla spójności
 * z pozostałymi repozytoriami oraz jawnym przekazywaniem CAST dla ENUM).
 *
 * <p>Tabela nie posiada kolumny {@code is_deleted}. Deaktywacja odbywa się
 * przez {@code is_active = false}.
 */
@Slf4j
@Repository
public class QueueRepository extends TenantAwareRepository {

    private final ObjectMapper objectMapper;

    @Autowired
    public QueueRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }


    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera paginowaną listę kolejek tenanta.
     *
     * <p>Opcjonalny filtr {@code name} (ILIKE). Zwraca tylko aktywne kolejki
     * ({@code is_active = true}).
     *
     * @param tenantId UUID tenanta
     * @param name     opcjonalny fragment nazwy (ILIKE), null = bez filtru
     * @param page     numer strony (0-based)
     * @param size     rozmiar strony
     * @return strona wyników opakowana w {@link PagedResponse}
     */
    @Transactional(readOnly = true)
    public PagedResponse<Queue> findAllByTenantId(UUID tenantId, String name, int page, int size) {
        setTenantContextInDb(tenantId);

        int offset = page * size;
        log.debug("[QueueRepo] Lista kolejek: tenant={}, name={}, page={}, size={}",
                tenantId, name, page, size);

        boolean hasNameFilter = name != null && !name.isBlank();

        String dataSql;
        String countSql;

        if (hasNameFilter) {
            dataSql = """
                    SELECT * FROM queue
                    WHERE tenant_id = CAST(:tenantId AS uuid)
                      AND LOWER(name) LIKE LOWER('%' || CAST(:name AS TEXT) || '%')
                    ORDER BY is_active DESC, name ASC
                    LIMIT :size OFFSET :offset
                    """;
            countSql = """
                    SELECT COUNT(*) FROM queue
                    WHERE tenant_id = CAST(:tenantId AS uuid)
                      AND LOWER(name) LIKE LOWER('%' || CAST(:name AS TEXT) || '%')
                    """;
        } else {
            dataSql = """
                    SELECT * FROM queue
                    WHERE tenant_id = CAST(:tenantId AS uuid)
                    ORDER BY is_active DESC, name ASC
                    LIMIT :size OFFSET :offset
                    """;
            countSql = """
                    SELECT COUNT(*) FROM queue
                    WHERE tenant_id = CAST(:tenantId AS uuid)
                    """;
        }

        @SuppressWarnings("unchecked")
        List<Queue> content;
        long total;

        if (hasNameFilter) {
            content = em.createNativeQuery(dataSql, Queue.class)
                    .setParameter("tenantId", tenantId.toString())
                    .setParameter("name", name)
                    .setParameter("size", size)
                    .setParameter("offset", offset)
                    .getResultList();

            total = ((Number) em.createNativeQuery(countSql)
                    .setParameter("tenantId", tenantId.toString())
                    .setParameter("name", name)
                    .getSingleResult()).longValue();
        } else {
            content = em.createNativeQuery(dataSql, Queue.class)
                    .setParameter("tenantId", tenantId.toString())
                    .setParameter("size", size)
                    .setParameter("offset", offset)
                    .getResultList();

            total = ((Number) em.createNativeQuery(countSql)
                    .setParameter("tenantId", tenantId.toString())
                    .getSingleResult()).longValue();
        }

        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        boolean first = page == 0;
        boolean last = page >= totalPages - 1;

        log.debug("[QueueRepo] Znaleziono {} kolejek (page={}, size={}, total={})",
                content.size(), page, size, total);

        return new PagedResponse<>(content, page, size, total, totalPages, first, last);
    }

    /**
     * Pobiera kolejkę po ID z weryfikacją tenanta.
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @return Optional z kolejką lub empty gdy nie istnieje
     */
    @Transactional(readOnly = true)
    public Optional<Queue> findByIdAndTenantId(UUID queueId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Queue> results = em.createQuery(
                        "SELECT q FROM Queue q WHERE q.queueId = :queueId AND q.tenantId = :tenantId",
                        Queue.class)
                .setParameter("queueId", queueId)
                .setParameter("tenantId", tenantId)
                .setMaxResults(1)
                .getResultList();

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Pobiera aktywną kolejkę przypisaną do danego adresu email.
     *
     * <p>Używane przez {@code EmailRoutingService} jako drugi krok routingu:
     * po sprawdzeniu reguł routingu, przed fallbackiem na kolejkę domyślną.
     *
     * @param emailAddress adres email (case-insensitive)
     * @param tenantId     UUID tenanta
     * @return Optional z kolejką lub empty gdy brak dopasowania
     */
    @Transactional(readOnly = true)
    public Optional<Queue> findByEmailAddressAndTenantId(String emailAddress, UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Queue> results = em.createNativeQuery("""
                SELECT * FROM queue
                WHERE tenant_id    = CAST(:tenantId AS uuid)
                  AND LOWER(email_address) = LOWER(CAST(:emailAddress AS TEXT))
                  AND is_active    = true
                LIMIT 1
                """, Queue.class)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("emailAddress", emailAddress)
                .getResultList();

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Sprawdza czy w kolejce istnieją aktywne kontakty.
     *
     * <p>Aktywne statusy kontaktów: QUEUED, ACTIVE, ON_HOLD.
     * Używane przy próbie usunięcia (deaktywacji) kolejki.
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @return true jeśli kolejka ma aktywne kontakty
     */
    @Transactional(readOnly = true)
    public boolean hasActiveContacts(UUID queueId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery("""
                SELECT COUNT(*) FROM contact
                WHERE queue_id  = CAST(:queueId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                  AND status    IN ('QUEUED', 'ACTIVE', 'ON_HOLD')
                """)
                .setParameter("queueId", queueId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        return count.longValue() > 0;
    }

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Tworzy nową kolejkę przez natywny INSERT.
     *
     * <p>Routing strategy jest przechowywana jako ENUM {@code routing_strategy}
     * w PostgreSQL – używamy CAST do właściwego typu.
     *
     * @param queue encja kolejki do zapisania (queueId musi być ustawiony)
     * @return zapisana encja
     */
    @Transactional
    public Queue insert(Queue queue) {
        assertSameTenant(queue.getTenantId());
        setTenantContextInDb(queue.getTenantId());

        log.info("[QueueRepo] Tworzenie kolejki: tenant={}, name={}, strategy={}",
                queue.getTenantId(), queue.getName(), queue.getRoutingStrategy());

        String requiredSkillsJson = queue.getRequiredSkills() != null
                ? skillsToJson(queue.getRequiredSkills())
                : "[]";
        String waitConfigJson = queue.getWaitConfig() != null
                ? queue.getWaitConfig()
                : "{\"announce_wait_time\": true, \"announce_interval_seconds\": 30}";

        em.createNativeQuery("""
                INSERT INTO queue (
                    queue_id, tenant_id, name, routing_strategy,
                    required_skills, sticky_agent_timeout_seconds,
                    max_concurrent_contacts_per_agent, wait_config,
                    email_address,
                    is_active, created_at, updated_at
                ) VALUES (
                    CAST(:queueId AS uuid),
                    CAST(:tenantId AS uuid),
                    :name,
                    CAST(:routingStrategy AS routing_strategy),
                    CAST(:requiredSkills AS jsonb),
                    :stickyAgentTimeoutSeconds,
                    :maxConcurrentContactsPerAgent,
                    CAST(:waitConfig AS jsonb),
                    :emailAddress,
                    :isActive,
                    :createdAt,
                    :updatedAt
                )
                """)
                .setParameter("queueId", queue.getQueueId().toString())
                .setParameter("tenantId", queue.getTenantId().toString())
                .setParameter("name", queue.getName())
                .setParameter("routingStrategy", queue.getRoutingStrategy())
                .setParameter("requiredSkills", requiredSkillsJson)
                .setParameter("stickyAgentTimeoutSeconds", queue.getStickyAgentTimeoutSeconds())
                .setParameter("maxConcurrentContactsPerAgent", queue.getMaxConcurrentContactsPerAgent())
                .setParameter("waitConfig", waitConfigJson)
                .setParameter("emailAddress", queue.getEmailAddress())
                .setParameter("isActive", queue.isActive())
                .setParameter("createdAt", queue.getCreatedAt())
                .setParameter("updatedAt", queue.getUpdatedAt())
                .executeUpdate();

        log.info("[QueueRepo] Kolejka utworzona: queueId={}, tenant={}",
                queue.getQueueId(), queue.getTenantId());
        return queue;
    }

    /**
     * Aktualizuje istniejącą kolejkę przez natywny UPDATE.
     *
     * @param queue encja kolejki z wypełnionym queueId i tenantId
     * @return liczba zaktualizowanych wierszy
     */
    @Transactional
    public int update(Queue queue) {
        assertSameTenant(queue.getTenantId());

        // Detach PRZED setTenantContextInDb – ta metoda wykonuje SQL (SELECT set_tenant_context),
        // co wyzwala auto-flush Hibernate przed detach. Musimy usunąć encję z persistence context
        // zanim jakiekolwiek zapytanie zostanie wykonane, żeby Hibernate nie generował własnego UPDATE
        // bez CAST dla kolumny routing_strategy (typ ENUM w PostgreSQL).
        em.detach(queue);

        setTenantContextInDb(queue.getTenantId());

        log.debug("[QueueRepo] Aktualizacja kolejki: queueId={}, tenant={}",
                queue.getQueueId(), queue.getTenantId());

        String requiredSkillsJson = queue.getRequiredSkills() != null
                ? skillsToJson(queue.getRequiredSkills())
                : "[]";
        String waitConfigJson = queue.getWaitConfig() != null
                ? queue.getWaitConfig()
                : "{\"announce_wait_time\": true, \"announce_interval_seconds\": 30}";

        int updated = em.createNativeQuery("""
                UPDATE queue SET
                    name                              = :name,
                    routing_strategy                  = CAST(:routingStrategy AS routing_strategy),
                    required_skills                   = CAST(:requiredSkills AS jsonb),
                    sticky_agent_timeout_seconds      = :stickyAgentTimeoutSeconds,
                    max_concurrent_contacts_per_agent = :maxConcurrentContactsPerAgent,
                    wait_config                       = CAST(:waitConfig AS jsonb),
                    email_address                     = :emailAddress,
                    is_active                         = :isActive,
                    updated_at                        = NOW()
                WHERE queue_id  = CAST(:queueId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("name", queue.getName())
                .setParameter("routingStrategy", queue.getRoutingStrategy())
                .setParameter("requiredSkills", requiredSkillsJson)
                .setParameter("stickyAgentTimeoutSeconds", queue.getStickyAgentTimeoutSeconds())
                .setParameter("maxConcurrentContactsPerAgent", queue.getMaxConcurrentContactsPerAgent())
                .setParameter("waitConfig", waitConfigJson)
                .setParameter("emailAddress", queue.getEmailAddress())
                .setParameter("isActive", queue.isActive())
                .setParameter("queueId", queue.getQueueId().toString())
                .setParameter("tenantId", queue.getTenantId().toString())
                .executeUpdate();

        log.debug("[QueueRepo] Zaktualizowano {} wierszy dla queueId={}", updated, queue.getQueueId());

        em.flush();
        em.clear();

        return updated;
    }

    /**
     * Deaktywuje kolejkę (is_active = false).
     *
     * <p>Tabela {@code queue} nie ma kolumny {@code is_deleted} –
     * deaktywacja jest odpowiednikiem soft-delete dla kolejek.
     *
     * @param queueId  UUID kolejki
     * @param tenantId UUID tenanta
     * @return liczba zaktualizowanych wierszy (0 = kolejka nie istnieje)
     */
    @Transactional
    public int softDelete(UUID queueId, UUID tenantId) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        log.info("[QueueRepo] Deaktywacja kolejki: queueId={}, tenant={}", queueId, tenantId);

        int updated = em.createNativeQuery("""
                UPDATE queue SET
                    is_active  = false,
                    updated_at = NOW()
                WHERE queue_id  = CAST(:queueId AS uuid)
                  AND tenant_id = CAST(:tenantId AS uuid)
                """)
                .setParameter("queueId", queueId.toString())
                .setParameter("tenantId", tenantId.toString())
                .executeUpdate();

        log.info("[QueueRepo] Deaktywacja kolejki: zaktualizowano {} wierszy dla queueId={}", updated, queueId);
        return updated;
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Serializuje listę skills do JSON string dla natywnych zapytań.
     *
     * <p>Używa {@link ObjectMapper} – poprawnie obsługuje znaki kontrolne
     * ({@code \n}, {@code \r}, {@code \t}), Unicode surrogate pairs i null bytes,
     * które ręczna serializacja przez StringBuilder pomijała.
     *
     * <p>Przy błędzie serializacji zwraca {@code "[]"} i loguje WARNING –
     * analogicznie do {@code ContactRepository.channelMetadataToJson}.
     */
    private String skillsToJson(List<String> skills) {
        if (skills == null || skills.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(skills);
        } catch (JsonProcessingException e) {
            log.warn("[QueueRepo] Błąd serializacji requiredSkills do JSON, fallback na []: {}", e.getMessage());
            return "[]";
        }
    }
}
