package com.contactcenter.domain.email;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Repozytorium dla encji {@link EmailMessage}.
 *
 * <p>Rozszerza {@link TenantAwareRepository} – każde zapytanie musi poprzedzać
 * wywołanie {@link #setTenantContextInDb()} aby RLS działało poprawnie.
 *
 * <p>Deduplicacja po {@code message_id_header} chroni przed wielokrotnym zapisem
 * tej samej wiadomości przy wielokrotnym pollingu IMAP.
 */
@Slf4j
@Repository
@Transactional
class EmailMessageRepository extends TenantAwareRepository {

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Zapisuje nową wiadomość email.
     *
     * @param message encja do zapisania – {@code tenantId} musi być ustawione
     * @return zapisana encja z wygenerowanym {@code id}
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId niezgodny z kontekstem
     */
    public EmailMessage save(EmailMessage message) {
        assertSameTenant(message.getTenantId());
        setTenantContextInDb();
        return em.merge(message);
    }

    /**
     * Aktualizuje istniejącą wiadomość email.
     *
     * @param message encja do aktualizacji
     * @return zaktualizowana encja
     */
    public EmailMessage update(EmailMessage message) {
        assertSameTenant(message.getTenantId(), message.getId());
        setTenantContextInDb();
        return em.merge(message);
    }

    // =========================================================================
    // BE-113: Retencja – odcięcie referencji (EPIC-29)
    // =========================================================================

    /**
     * Zeruje {@code contact_id} dla wiadomości wskazujących na usuwane kontakty (retencja EPIC-29,
     * BE-113 – kategoria CONTACT_INTERACTIONS).
     *
     * <p>Kolumna {@code contact_id} jest nullable od V028 (przychodzące wiadomości zapisywane
     * przed przypisaniem do kontaktu przez routing). Metoda TYLKO odcina referencję (bulk UPDATE) –
     * NIE usuwa wierszy {@code email_message}, zgodnie z zakresem BE-113.
     *
     * <p>Bulk JPQL UPDATE (nie {@code em.merge}) – omija walidację {@code nullable=false} na
     * poziomie encji {@link EmailMessage#getContactId()} (adnotacja JPA dotyczy generowania DDL,
     * nie ma zastosowania do zapytań masowych).
     *
     * @param tenantId   UUID tenanta (RLS + cross-tenant safety)
     * @param contactIds lista UUID usuniętych kontaktów – pusta lista jest no-opem
     * @return liczba zaktualizowanych wierszy
     * @deprecated EPIC-30 (D1 = A, BE-125): purge kontaktu USUWA wiadomości wraz z obiektami S3
     *             ({@link #findAttachmentsByContactIds} + {@link #deleteByIds}, orkiestracja w
     *             {@link EmailMessageService#purgeByContactIds}) zamiast odcinać referencję i
     *             zostawiać PII. Podpięcie w {@code RetentionPurgeServiceImpl} i usunięcie tej
     *             metody: BE-126 (przy D1 = C BE-130 przywraca odcinanie referencji).
     */
    @Deprecated
    public int detachContactReferences(UUID tenantId, List<UUID> contactIds) {
        if (contactIds == null || contactIds.isEmpty()) {
            return 0;
        }
        setTenantContextInDb(tenantId);

        int updated = em.createQuery(
                        "UPDATE EmailMessage m SET m.contactId = NULL " +
                        "WHERE m.tenantId = :tenantId AND m.contactId IN :contactIds")
                .setParameter("tenantId", tenantId)
                .setParameter("contactIds", contactIds)
                .executeUpdate();

        log.info("[EmailMessageRepo] Odcięto contact_id: tenant={}, kontaktów={}, wiadomości={}",
                tenantId, contactIds.size(), updated);
        return updated;
    }

    // =========================================================================
    // BE-125: Retencja – usuwanie wiadomości wraz z obiektami S3 (EPIC-30)
    // =========================================================================

    /** Maksymalna liczba elementów listy w jednym {@code IN (...)} (limit 32767 parametrów PostgreSQL JDBC). */
    private static final int IN_LIST_CHUNK_SIZE = 1000;

    /** SQL fazy 1 — package-private, żeby test integracyjny mógł zrobić {@code EXPLAIN} dokładnie tego zapytania. */
    static final String FIND_ATTACHMENTS_SQL = """
            SELECT message_id, contact_id, CAST(attachments AS text)
            FROM email_message
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND contact_id IN (:contactIds)
            """;

    /** SQL fazy 3 — package-private z tego samego powodu co {@link #FIND_ATTACHMENTS_SQL}. */
    static final String DELETE_BY_IDS_SQL = """
            DELETE FROM email_message
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND message_id IN (:messageIds)
            RETURNING message_id
            """;

    /**
     * Wiersz do fazy S3 purge: klucz główny wiadomości, kontakt (do {@code contactIdsBlocked}; może
     * być {@code null} dla wiadomości osieroconych — BE-127) oraz surowy JSONB {@code attachments}
     * jako tekst.
     *
     * <p>Po partycjonowaniu {@code email_message} (DB-067/BE-134) PK staje się złożony
     * ({@code message_id, message_at}) — wtedy rekord musi nieść także {@code messageAt}, a
     * {@link #deleteByIds} identyfikować wiersze pełnym kluczem (wzorzec
     * {@code ContactRepository#deleteBatchOlderThan}).
     */
    record AttachmentsRow(UUID messageId, UUID contactId, String attachmentsJson) {}

    /**
     * Faza 1 purge „S3 przed wierszem": zwraca wiadomości tenanta powiązane z podanymi kontaktami
     * razem z metadanymi załączników — bez usuwania czegokolwiek.
     *
     * <p><strong>Natywny SQL z projekcją skalarną</strong> ({@code Object[]}), nie
     * {@code createNativeQuery(sql, EmailMessage.class)} — w tym projekcie natywne zapytania z
     * {@code resultClass} i kolumnami enum kończyły się {@code ClassCastException} (EPIC-29), a do
     * fazy S3 potrzebne są tylko trzy kolumny. JSONB jest rzutowany na tekst po stronie SQL;
     * parsowanie i odporność na uszkodzone dane: {@code EmailAttachmentKeys#extractS3Keys}.
     *
     * <p><strong>Indeks:</strong> {@code contact_id IN (...)} korzysta z
     * {@code idx_email_message_contact (contact_id, received_at DESC)}; filtr {@code tenant_id}
     * to dodatkowa bariera izolacji (obok RLS). Zapytanie po {@code contact_id} bez klucza
     * partycji działa też na tabeli partycjonowanej (DB-067) — PostgreSQL odpyta indeksy
     * poszczególnych partycji.
     *
     * <p>Transakcja tylko-do-odczytu jest krótka i kończy się PRZED wywołaniami S3 (nie trzymamy
     * połączenia z puli przez I/O).
     *
     * @param tenantId   UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param contactIds kontakty, których wiadomości mają zostać usunięte — pusta lista = pusty wynik
     * @return wiadomości do przetworzenia (nigdy {@code null})
     * @throws IllegalStateException gdy {@code TenantContext} nie jest ustawiony
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    @Transactional(readOnly = true)
    public List<AttachmentsRow> findAttachmentsByContactIds(UUID tenantId, List<UUID> contactIds) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);
        if (contactIds == null || contactIds.isEmpty()) {
            return List.of();
        }

        List<AttachmentsRow> result = new ArrayList<>();
        for (int from = 0; from < contactIds.size(); from += IN_LIST_CHUNK_SIZE) {
            List<UUID> chunk = contactIds.subList(from, Math.min(from + IN_LIST_CHUNK_SIZE, contactIds.size()));

            @SuppressWarnings("unchecked")
            List<Object[]> rows = em.createNativeQuery(FIND_ATTACHMENTS_SQL)
                    .setParameter("tenantId", tenantId.toString())
                    .setParameter("contactIds", chunk)
                    .getResultList();

            for (Object[] row : rows) {
                result.add(toAttachmentsRow(row));
            }
        }

        log.debug("[EmailMessageRepo] Do purge: tenant={}, kontaktów={}, wiadomości={}",
                tenantId, contactIds.size(), result.size());
        return result;
    }

    /**
     * Faza 3 purge „S3 przed wierszem": usuwa wskazane wiadomości tenanta i zwraca ID wierszy
     * FAKTYCZNIE usunięte ({@code DELETE … RETURNING message_id}).
     *
     * <p>Wywołujący przekazuje wyłącznie wiadomości, których wszystkie obiekty S3 zostały już
     * usunięte. Wiersze identyfikowane pełnym kluczem głównym (dziś {@code message_id}); NIGDY
     * {@code ctid} — na tabeli partycjonowanej (DB-067) {@code ctid} nie jest unikalny globalnie i
     * DELETE po nim usuwa cudze wiersze (zweryfikowane empirycznie w BE-113, zob.
     * {@code ContactRepository#deleteBatchOlderThan}).
     * Filtr {@code tenant_id} zostaje jako bariera izolacji.
     *
     * <p>Zwracanie faktycznie usuniętych ID (a nie liczby zleconych) jest istotne: pod rolą bez
     * BYPASSRLS DELETE bez polityki {@code FOR DELETE} usuwa 0 wierszy BEZ błędu (DESIGN §2 U8,
     * R1) — wołający zobaczy różnicę i nie usunie kontaktu, którego wiadomości zostały.
     *
     * @param tenantId   UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param messageIds klucze główne wiadomości do usunięcia — pusta kolekcja = pusty wynik
     * @return zbiór {@code message_id} faktycznie usuniętych wierszy (nigdy {@code null})
     * @throws IllegalStateException gdy {@code TenantContext} nie jest ustawiony
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    @Transactional
    public Set<UUID> deleteByIds(UUID tenantId, Collection<UUID> messageIds) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        Set<UUID> deleted = new HashSet<>();
        if (messageIds == null || messageIds.isEmpty()) {
            return deleted;
        }

        List<UUID> ids = List.copyOf(messageIds);
        for (int from = 0; from < ids.size(); from += IN_LIST_CHUNK_SIZE) {
            List<UUID> chunk = ids.subList(from, Math.min(from + IN_LIST_CHUNK_SIZE, ids.size()));

            @SuppressWarnings("unchecked")
            List<Object> returned = em.createNativeQuery(DELETE_BY_IDS_SQL)
                    .setParameter("tenantId", tenantId.toString())
                    .setParameter("messageIds", chunk)
                    .getResultList();

            for (Object id : returned) {
                deleted.add(toUuid(id));
            }
        }

        log.info("[EmailMessageRepo] Usunięto wiadomości: tenant={}, zlecono={}, usunięto={}",
                tenantId, ids.size(), deleted.size());
        return deleted;
    }

    /**
     * Mapuje wiersz projekcji {@code SELECT message_id, contact_id, CAST(attachments AS text)}
     * ({@link #FIND_ATTACHMENTS_SQL}) na {@link AttachmentsRow}. {@code contact_id} bywa {@code NULL}
     * (wiadomość osierocona — BE-127 użyje tego samego mapowania) i wtedy trafia do rekordu jako
     * {@code null}. Package-private, żeby test jednostkowy pokrył ścieżkę {@code NULL} bez bazy.
     */
    static AttachmentsRow toAttachmentsRow(Object[] row) {
        return new AttachmentsRow(toUuid(row[0]), toUuid(row[1]), (String) row[2]);
    }

    /** Konwersja wartości kolumny {@code uuid} z natywnego zapytania; {@code null} (kolumna NULL) → {@code null}. */
    private static UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera wiadomość po UUID (PK).
     *
     * @param messageId UUID wiadomości (kolumna message_id)
     * @return Optional z wiadomością lub empty
     */
    @Transactional(readOnly = true)
    public Optional<EmailMessage> findById(UUID messageId) {
        setTenantContextInDb();
        return Optional.ofNullable(em.find(EmailMessage.class, messageId));
    }

    /**
     * Wyszukuje wiadomość po nagłówku RFC 2822 Message-ID (deduplikacja IMAP).
     *
     * @param messageIdHeader wartość nagłówka Message-ID, np. {@code <abc@domain.com>}
     * @param tenantId        UUID tenanta (dla RLS + UNIQUE constraint)
     * @return Optional z wiadomością lub empty jeśli brak duplikatu
     */
    @Transactional(readOnly = true)
    public Optional<EmailMessage> findByMessageIdHeader(String messageIdHeader, UUID tenantId) {
        setTenantContextInDb(tenantId);
        List<EmailMessage> results = em.createQuery(
                        "SELECT m FROM EmailMessage m " +
                        "WHERE m.messageIdHeader = :header AND m.tenantId = :tenantId",
                        EmailMessage.class)
                .setParameter("header", messageIdHeader)
                .setParameter("tenantId", tenantId)
                .setMaxResults(1)
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Pobiera pierwszą wiadomość INBOUND powiązaną z kontaktem (root kontaktu email).
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @return Optional z wiadomością lub empty gdy brak
     */
    @Transactional(readOnly = true)
    public Optional<EmailMessage> findFirstInboundByContactId(UUID contactId, UUID tenantId) {
        setTenantContextInDb(tenantId);
        List<EmailMessage> results = em.createQuery(
                        "SELECT m FROM EmailMessage m " +
                        "WHERE m.contactId = :contactId AND m.tenantId = :tenantId " +
                        "AND m.direction = 'INBOUND' " +
                        "ORDER BY m.receivedAt ASC NULLS LAST",
                        EmailMessage.class)
                .setParameter("contactId", contactId)
                .setParameter("tenantId", tenantId)
                .setMaxResults(1)
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Pobiera historię wiadomości dla kontaktu (paginacja).
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @param pageable  parametry paginacji
     * @return strona wiadomości posortowanych po {@code received_at DESC}
     */
    @Transactional(readOnly = true)
    public Page<EmailMessage> findByContactId(UUID contactId, UUID tenantId, Pageable pageable) {
        setTenantContextInDb(tenantId);

        List<EmailMessage> content = em.createQuery(
                        "SELECT m FROM EmailMessage m " +
                        "WHERE m.contactId = :contactId AND m.tenantId = :tenantId " +
                        "ORDER BY m.receivedAt DESC NULLS LAST, m.createdAt DESC",
                        EmailMessage.class)
                .setParameter("contactId", contactId)
                .setParameter("tenantId", tenantId)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        Long total = em.createQuery(
                        "SELECT COUNT(m) FROM EmailMessage m " +
                        "WHERE m.contactId = :contactId AND m.tenantId = :tenantId",
                        Long.class)
                .setParameter("contactId", contactId)
                .setParameter("tenantId", tenantId)
                .getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    /**
     * Pobiera listę wiadomości w wątku emailowym (pogrupowane po In-Reply-To).
     *
     * <p>Wątek identyfikowany jest przez pierwotny {@code messageIdHeader}.
     * Zwraca oryginalną wiadomość + wszystkie odpowiedzi (In-Reply-To łańcuch).
     *
     * @param originalMessageIdHeader nagłówek Message-ID wiadomości oryginalnej
     * @param tenantId                UUID tenanta
     * @param pageable                parametry paginacji
     * @return strona wiadomości wątku posortowanych chronologicznie
     */
    @Transactional(readOnly = true)
    public Page<EmailMessage> findByThreadRootMessageId(
            String originalMessageIdHeader, UUID tenantId, Pageable pageable) {
        setTenantContextInDb(tenantId);

        // Wątek = oryginalna wiadomość + wszystkie z In-Reply-To = oryginalny Message-ID
        List<EmailMessage> content = em.createQuery(
                        "SELECT m FROM EmailMessage m " +
                        "WHERE m.tenantId = :tenantId " +
                        "AND (m.messageIdHeader = :rootId OR m.inReplyTo = :rootId) " +
                        "ORDER BY m.createdAt ASC",
                        EmailMessage.class)
                .setParameter("tenantId", tenantId)
                .setParameter("rootId", originalMessageIdHeader)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        Long total = em.createQuery(
                        "SELECT COUNT(m) FROM EmailMessage m " +
                        "WHERE m.tenantId = :tenantId " +
                        "AND (m.messageIdHeader = :rootId OR m.inReplyTo = :rootId)",
                        Long.class)
                .setParameter("tenantId", tenantId)
                .setParameter("rootId", originalMessageIdHeader)
                .getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }

    /**
     * Pobiera paginowaną listę wiadomości dla tenanta (widok listy).
     *
     * @param pageable parametry paginacji
     * @return strona wiadomości posortowanych po {@code created_at DESC}
     */
    @Transactional(readOnly = true)
    public Page<EmailMessage> findAll(Pageable pageable) {
        setTenantContextInDb();
        UUID tenantId = currentTenantId();

        List<EmailMessage> content = em.createQuery(
                        "SELECT m FROM EmailMessage m " +
                        "WHERE m.tenantId = :tenantId " +
                        "ORDER BY m.createdAt DESC",
                        EmailMessage.class)
                .setParameter("tenantId", tenantId)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        Long total = em.createQuery(
                        "SELECT COUNT(m) FROM EmailMessage m WHERE m.tenantId = :tenantId",
                        Long.class)
                .setParameter("tenantId", tenantId)
                .getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }
}
