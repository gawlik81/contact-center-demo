package com.contactcenter.domain.contact;

import com.contactcenter.domain.exception.CrossTenantAccessException;
import com.contactcenter.domain.repository.TenantAwareRepository;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium zdarzeń kontaktu (historia etapów przetwarzania).
 *
 * <p>Operacje zapisu używają natywnego SQL przez {@link jakarta.persistence.EntityManager}
 * – zgodnie z wzorcem {@code ContactRepository}. Każda metoda wywołuje
 * {@link #setTenantContextInDb(UUID)} dla RLS oraz {@link #assertSameTenant(UUID)}
 * przed operacjami zapisu.
 *
 * <p>Po natywnym UPDATE wywoływane jest {@code em.flush(); em.clear()} w celu wyczyszczenia
 * L1 cache Hibernate – trigger DB mógł zmienić {@code duration_seconds}.
 */
@Slf4j
@Repository
class ContactEventRepository extends TenantAwareRepository {

    private final ObjectMapper objectMapper;

    public ContactEventRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // Zapis – natywny INSERT
    // =========================================================================

    /**
     * Zapisuje nowe zdarzenie kontaktu przez natywny INSERT.
     *
     * @param event encja zdarzenia (eventId musi być ustawiony)
     */
    @Transactional
    public void save(ContactEvent event) {
        // TenantContext może być wyczyszczony gdy zapis jest wywołany z kontekstu
        // webhooka Twilio (TenantContext.clear() w finally przed wywołaniem IVR).
        // Gdy kontekst jest ustawiony — weryfikujemy spójność; gdy brak — ufamy
        // tenantId z encji (pochodzi z zaufanych serwisów wewnętrznych).
        UUID contextTenant = TenantContext.getTenantIdOrNull();
        if (contextTenant != null && !contextTenant.equals(event.getTenantId())) {
            throw new CrossTenantAccessException(event.getEventId(), contextTenant, event.getTenantId());
        }
        setTenantContextInDb(event.getTenantId());

        log.debug("[ContactEventRepo] Zapis zdarzenia: contactId={}, stage={}, tenant={}",
                event.getContactId(), event.getStage(), event.getTenantId());

        em.createNativeQuery("""
                INSERT INTO contact_event (event_id, contact_id, tenant_id, stage, started_at, ended_at, metadata)
                VALUES (
                    CAST(:eventId   AS uuid),
                    CAST(:contactId AS uuid),
                    CAST(:tenantId  AS uuid),
                    :stage,
                    :startedAt,
                    :endedAt,
                    CAST(:metadata  AS jsonb)
                )
                """)
                .setParameter("eventId",    event.getEventId().toString())
                .setParameter("contactId",  event.getContactId().toString())
                .setParameter("tenantId",   event.getTenantId().toString())
                .setParameter("stage",      event.getStage())
                .setParameter("startedAt",  event.getStartedAt())
                .setParameter("endedAt",    event.getEndedAt())
                .setParameter("metadata",   metadataToJson(event.getMetadata()))
                .executeUpdate();

        log.debug("[ContactEventRepo] Zdarzenie zapisane: eventId={}", event.getEventId());
    }

    // =========================================================================
    // Zamknięcie ostatniego otwartego etapu – natywny UPDATE
    // =========================================================================

    /**
     * Zamyka ostatni otwarty etap danego stage dla kontaktu przez ustawienie {@code ended_at}.
     *
     * <p>Zapytanie identyfikuje najnowszy rekord z {@code ended_at IS NULL} dla podanej
     * kombinacji (contactId, tenantId, stage) i ustawia mu {@code ended_at}. Trigger DB
     * automatycznie oblicza {@code duration_seconds}.
     *
     * <p>Po wykonaniu UPDATE wywoływane jest {@code em.flush(); em.clear()} w celu
     * wyczyszczenia L1 cache Hibernate.
     *
     * <p><strong>BE-117 – analiza czy WHERE wymaga dodania {@code started_at}:</strong>
     * po partycjonowaniu (V085) PK tabeli to {@code (event_id, started_at)}, więc UPDATE
     * adresujący wiersz WYŁĄCZNIE po {@code event_id} musiałby doprecyzować partycję przez
     * {@code started_at}. Ten UPDATE tego nie robi świadomie – zarówno subquery, jak i
     * zapytanie zewnętrzne identyfikują wiersz przez kombinację {@code contact_id},
     * {@code tenant_id}, {@code stage} i {@code ended_at IS NULL} (nie tylko {@code event_id}),
     * więc jest to poprawne semantycznie bez zmian. Jedyny efekt uboczny braku
     * {@code started_at} w WHERE to brak partition pruning (PostgreSQL przeszuka wszystkie
     * partycje zamiast jednej) – akceptowalne przy dzisiejszej liczbie partycji
     * (kilka miesięcy + DEFAULT); ewentualną optymalizację pozostawiono jako osobny temat
     * wydajnościowy, poza zakresem tego ticketu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @param stage     nazwa etapu (np. "IVR", "AGENT")
     * @param endedAt   czas zakończenia etapu
     * @return liczba zaktualizowanych wierszy (0 = brak otwartego etapu)
     */
    @Transactional
    public int closeLastOpen(UUID contactId, UUID tenantId, String stage, Instant endedAt) {
        setTenantContextInDb(tenantId);

        log.debug("[ContactEventRepo] Zamykanie etapu: contactId={}, stage={}, endedAt={}",
                contactId, stage, endedAt);

        int updated = em.createNativeQuery("""
                UPDATE contact_event
                SET ended_at = :endedAt
                WHERE contact_id = CAST(:contactId AS uuid)
                  AND tenant_id  = CAST(:tenantId  AS uuid)
                  AND stage      = :stage
                  AND ended_at IS NULL
                  AND event_id = (
                      SELECT event_id FROM contact_event
                      WHERE contact_id = CAST(:contactId AS uuid)
                        AND tenant_id  = CAST(:tenantId  AS uuid)
                        AND stage      = :stage
                        AND ended_at IS NULL
                      ORDER BY started_at DESC
                      LIMIT 1
                  )
                """)
                .setParameter("endedAt",    endedAt)
                .setParameter("contactId",  contactId.toString())
                .setParameter("tenantId",   tenantId.toString())
                .setParameter("stage",      stage)
                .executeUpdate();

        // Wyczyść L1 cache Hibernate po natywnym UPDATE – trigger DB mógł zmienić
        // duration_seconds. Bez flush+clear kolejny JPQL select trafiłby w cache.
        em.flush();
        em.clear();

        log.debug("[ContactEventRepo] Zamknięto {} rekordów etapu {} dla contactId={}", updated, stage, contactId);
        return updated;
    }

    // =========================================================================
    // Odczyt – historia kontaktu
    // =========================================================================

    /**
     * Zwraca pełną historię etapów kontaktu posortowaną chronologicznie.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @return lista zdarzeń posortowana po started_at ASC
     */
    @Transactional(readOnly = true)
    public List<ContactEvent> findByContactId(UUID contactId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        log.debug("[ContactEventRepo] Historia kontaktu: contactId={}, tenant={}", contactId, tenantId);

        return em.createQuery(
                "SELECT e FROM ContactEvent e " +
                "WHERE e.contactId = :contactId AND e.tenantId = :tenantId " +
                "ORDER BY e.startedAt ASC",
                ContactEvent.class)
                .setParameter("contactId", contactId)
                .setParameter("tenantId",  tenantId)
                .getResultList();
    }

    // =========================================================================
    // Odczyt – ostatni otwarty etap
    // =========================================================================

    /**
     * Zwraca ostatni otwarty (niezakończony) etap danego stage dla kontaktu.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @param stage     nazwa etapu (np. "AGENT")
     * @return Optional z ostatnim otwartym zdarzeniem lub empty gdy brak
     */
    @Transactional(readOnly = true)
    public Optional<ContactEvent> findLastOpen(UUID contactId, UUID tenantId, String stage) {
        setTenantContextInDb(tenantId);

        log.debug("[ContactEventRepo] Ostatni otwarty etap: contactId={}, stage={}", contactId, stage);

        List<ContactEvent> result = em.createQuery(
                "SELECT e FROM ContactEvent e " +
                "WHERE e.contactId = :contactId AND e.tenantId = :tenantId " +
                "  AND e.stage = :stage AND e.endedAt IS NULL " +
                "ORDER BY e.startedAt DESC",
                ContactEvent.class)
                .setParameter("contactId", contactId)
                .setParameter("tenantId",  tenantId)
                .setParameter("stage",     stage)
                .setMaxResults(1)
                .getResultList();

        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Serializuje mapę metadata do JSON string dla natywnych zapytań PostgreSQL JSONB.
     *
     * <p>Przy błędzie serializacji zwraca {@code "{}"} i loguje warning – zgubienie
     * metadanych jest mniej krytyczne niż zablokowanie zapisu historii kontaktu.
     */
    private String metadataToJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("[ContactEventRepo] Błąd serializacji metadata: {}", e.getMessage());
            return "{}";
        }
    }
}
