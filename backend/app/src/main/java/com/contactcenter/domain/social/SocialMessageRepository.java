package com.contactcenter.domain.social;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Repozytorium dla encji {@link SocialMessage}.
 *
 * <p>Rozszerza {@link TenantAwareRepository} – każde zapytanie musi poprzedzać
 * wywołanie {@link #setTenantContextInDb()} aby RLS działało poprawnie.
 *
 * <p>Deduplikacja opiera się na {@code external_message_id} + {@code tenant_id}
 * (constraint {@code uq_social_message_external_id} w DB).
 */
@Slf4j
@Repository
@Transactional
class SocialMessageRepository extends TenantAwareRepository {

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Zapisuje nową wiadomość social media.
     *
     * @param message encja do zapisania – {@code tenantId} musi być ustawione
     * @return zapisana encja z wygenerowanym {@code messageId}
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId niezgodny z kontekstem
     */
    public SocialMessage save(SocialMessage message) {
        assertSameTenant(message.getTenantId());
        setTenantContextInDb();
        log.debug("[SocialMessageRepo] Zapisuję wiadomość: externalId={}, platform={}, tenant={}",
                message.getExternalMessageId(), message.getPlatform(), message.getTenantId());
        return em.merge(message);
    }

    // =========================================================================
    // BE-113: Retencja – odcięcie referencji (EPIC-29)
    // =========================================================================

    /**
     * Zeruje {@code contact_id} dla wiadomości wskazujących na usuwane kontakty (retencja EPIC-29,
     * BE-113 – kategoria CONTACT_INTERACTIONS).
     *
     * <p>Kolumna {@code contact_id} jest nullable od V028. Metoda TYLKO odcina referencję (bulk
     * UPDATE) – NIE usuwa wierszy {@code social_message}, zgodnie z zakresem BE-113.
     *
     * <p>Bulk JPQL UPDATE (nie {@code em.merge}) – omija walidację {@code nullable=false} na
     * poziomie encji {@link SocialMessage#getContactId()} (adnotacja JPA dotyczy generowania DDL;
     * kolumna w bazie jest faktycznie nullable od V028).
     *
     * @param tenantId   UUID tenanta (RLS + cross-tenant safety)
     * @param contactIds lista UUID usuniętych kontaktów – pusta lista jest no-opem
     * @return liczba zaktualizowanych wierszy
     * @deprecated EPIC-30 (D1 = A, BE-125): purge kontaktu USUWA wiadomości ({@link #purgeByContactIds})
     *             zamiast odcinać referencję i zostawiać PII. Podpięcie w
     *             {@code RetentionPurgeServiceImpl} i usunięcie tej metody: BE-126 (przy D1 = C
     *             BE-130 przywraca odcinanie referencji).
     */
    @Deprecated
    public int detachContactReferences(UUID tenantId, List<UUID> contactIds) {
        if (contactIds == null || contactIds.isEmpty()) {
            return 0;
        }
        setTenantContextInDb(tenantId);

        int updated = em.createQuery(
                        "UPDATE SocialMessage m SET m.contactId = NULL " +
                        "WHERE m.tenantId = :tenantId AND m.contactId IN :contactIds")
                .setParameter("tenantId", tenantId)
                .setParameter("contactIds", contactIds)
                .executeUpdate();

        log.info("[SocialMessageRepo] Odcięto contact_id: tenant={}, kontaktów={}, wiadomości={}",
                tenantId, contactIds.size(), updated);
        return updated;
    }

    // =========================================================================
    // BE-125: Retencja – usuwanie wiadomości (EPIC-30)
    // =========================================================================

    /** Maksymalna liczba elementów listy w jednym {@code IN (...)} (limit 32767 parametrów PostgreSQL JDBC). */
    private static final int IN_LIST_CHUNK_SIZE = 1000;

    /** SQL purge — package-private, żeby test integracyjny mógł zrobić {@code EXPLAIN} dokładnie tego zapytania. */
    static final String DELETE_BY_CONTACT_IDS_SQL = """
            DELETE FROM social_message
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND contact_id IN (:contactIds)
            """;

    /**
     * Usuwa wiadomości social tenanta wskazanych kontaktów (retencja EPIC-30, BE-125, D1 = A).
     *
     * <p><strong>Bez S3:</strong> domena social nie ma obiektów w S3 — {@code attachments} to (dziś
     * puste) metadane/URL-e platform (BE-124 §3), więc wystarcza jeden {@code DELETE}. Gdyby w
     * {@code attachments} pojawiły się kiedyś obiekty do sprzątnięcia, ta metoda musi przejść na
     * schemat „S3 przed wierszem" z {@code EmailMessageService#purgeByContactIds}.
     *
     * <p>Pojedynczy {@code DELETE … WHERE tenant_id AND contact_id IN (...)} — bez podzapytania z
     * {@code LIMIT}, więc nie ma potrzeby identyfikacji wierszy ani {@code ctid}. {@code contact_id IN}
     * korzysta z {@code idx_social_message_contact (contact_id, sent_at DESC)}; zapytanie po
     * {@code contact_id} bez klucza partycji działa też na tabeli partycjonowanej (DB-065) —
     * PostgreSQL odpyta indeksy poszczególnych partycji. Filtr {@code tenant_id} to bariera izolacji
     * obok RLS.
     *
     * @param tenantId   UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param contactIds kontakty, których wiadomości mają zostać usunięte — {@code null}/pusta lista = 0
     * @return liczba usuniętych wierszy
     * @throws IllegalStateException gdy {@code TenantContext} nie jest ustawiony
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    @Transactional
    public int purgeByContactIds(UUID tenantId, List<UUID> contactIds) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);
        if (contactIds == null || contactIds.isEmpty()) {
            return 0;
        }

        int deleted = 0;
        for (int from = 0; from < contactIds.size(); from += IN_LIST_CHUNK_SIZE) {
            List<UUID> chunk = contactIds.subList(from, Math.min(from + IN_LIST_CHUNK_SIZE, contactIds.size()));
            deleted += em.createNativeQuery(DELETE_BY_CONTACT_IDS_SQL)
                    .setParameter("tenantId", tenantId.toString())
                    .setParameter("contactIds", chunk)
                    .executeUpdate();
        }

        log.info("[SocialMessageRepo] Usunięto wiadomości: tenant={}, kontaktów={}, wiadomości={}",
                tenantId, contactIds.size(), deleted);
        return deleted;
    }

    // =========================================================================
    // BE-127: Retencja – sweep wiadomości OSIEROCONYCH (contact_id IS NULL) wg wieku (EPIC-30)
    // =========================================================================

    /** SQL dry-run/count — package-private dla testu EXPLAIN. */
    static final String COUNT_ORPHANS_SQL = """
            SELECT COUNT(*)
            FROM social_message
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND contact_id IS NULL
              AND sent_at < :cutoff
            """;

    /** SQL pierwszej strony sweepu sierot (brak kursora) — package-private dla testu EXPLAIN. */
    static final String FIND_ORPHANS_FIRST_PAGE_SQL = """
            SELECT message_id, sent_at
            FROM social_message
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND contact_id IS NULL
              AND sent_at < :cutoff
            ORDER BY sent_at, message_id
            LIMIT :batchSize
            """;

    /** SQL kolejnych stron sweepu sierot (kursor keyset) — package-private dla testu EXPLAIN. */
    static final String FIND_ORPHANS_NEXT_PAGE_SQL = """
            SELECT message_id, sent_at
            FROM social_message
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND contact_id IS NULL
              AND sent_at < :cutoff
              AND (sent_at, message_id) > (:cursorMessageAt, CAST(:cursorMessageId AS uuid))
            ORDER BY sent_at, message_id
            LIMIT :batchSize
            """;

    /** SQL usuwania sierot po ID — package-private dla testu EXPLAIN. */
    static final String DELETE_ORPHANS_BY_IDS_SQL = """
            DELETE FROM social_message
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND message_id IN (:messageIds)
            RETURNING message_id
            """;

    /**
     * Kandydat sierocy: klucz główny wiadomości i wartość {@code sent_at} — do zbudowania
     * {@link SocialOrphanCursor} kolejnej strony (jak {@code EmailMessageRepository.OrphanCandidate},
     * ale bez {@code attachmentsJson}: social nie ma S3, więc {@link #deleteOrphansByIds} usuwa
     * wprost po {@code messageId}, bez fazy S3).
     */
    record OrphanCandidate(UUID messageId, Instant messageAt) {
    }

    /**
     * Liczy wiadomości social OSIEROCONE ({@code contact_id IS NULL}) starsze niż {@code cutoff} —
     * dry-run (WP-4) i dashboard/badge (BE-128).
     *
     * <p><strong>Bez filtra resztkowego</strong> (w odróżnieniu od
     * {@code EmailMessageRepository#countOrphansOlderThan}): w tej domenie {@code contact_id} jest
     * ZAWSZE ustawiany SYNCHRONICZNIE przed zapisem wiadomości
     * ({@code SocialMessageServiceImpl#processIncomingMessage}, krok 4 — kontakt tworzony/dobierany
     * PRZED {@code save}) — świeża, jeszcze nieprzypisana wiadomość social z {@code contact_id IS
     * NULL} nie istnieje strukturalnie (w odróżnieniu od e-mail, gdzie routing jest asynchroniczny
     * względem zapisu, BE-124 §2 U1). Jedyne źródło sierot social to
     * {@code detachContactReferences} (odcięcie referencji przy purge kontaktu w LEGACY trybie) —
     * taki kontakt był z definicji już starszy niż cutoff w chwili odcięcia, więc „świeża sierota"
     * jest tu strukturalnie nieosiągalna.
     *
     * @param tenantId UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param cutoff   granica czasowa — kandydują wiadomości z {@code sent_at} < {@code cutoff}
     * @return liczba kwalifikujących się wiadomości
     * @throws IllegalStateException gdy {@code TenantContext} nie jest ustawiony
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    @Transactional(readOnly = true)
    public long countOrphansOlderThan(UUID tenantId, Instant cutoff) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery(COUNT_ORPHANS_SQL)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("cutoff", cutoff)
                .getSingleResult();
        return count.longValue();
    }

    /**
     * Zwraca stronę kandydatów sierocych do usunięcia (BE-127) — analogicznie do
     * {@code EmailMessageRepository#findOrphansOlderThan}/{@code ContactRepository#findContactIdsOlderThan}
     * (BE-126): NIC nie usuwa, tylko wybiera kandydatów; wywołujący
     * ({@code SocialMessageServiceImpl#purgeOrphansOlderThan}) usuwa je przez
     * {@link #deleteOrphansByIds}.
     *
     * <p>Strategia H-1 (jak email/BE-126): {@code ORDER BY sent_at, message_id} deterministyczny,
     * stronicowanie keyset — kursor przesuwa się o CAŁĄ stronę niezależnie od wyniku usunięcia.
     *
     * @param tenantId  UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param cutoff    granica czasowa — kandydują wiadomości z {@code sent_at} < {@code cutoff}
     * @param cursor    ostatni kandydat poprzedniej strony ({@code null} dla pierwszej strony)
     * @param batchSize maksymalna liczba kandydatów na stronę
     * @return strona kandydatów posortowana rosnąco po {@code (sent_at, message_id)}
     * @throws IllegalStateException gdy {@code TenantContext} nie jest ustawiony
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    @Transactional(readOnly = true)
    public List<OrphanCandidate> findOrphansOlderThan(
            UUID tenantId, Instant cutoff, SocialOrphanCursor cursor, int batchSize) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        var query = cursor == null
                ? em.createNativeQuery(FIND_ORPHANS_FIRST_PAGE_SQL)
                        .setParameter("tenantId", tenantId.toString())
                        .setParameter("cutoff", cutoff)
                        .setParameter("batchSize", batchSize)
                : em.createNativeQuery(FIND_ORPHANS_NEXT_PAGE_SQL)
                        .setParameter("tenantId", tenantId.toString())
                        .setParameter("cutoff", cutoff)
                        .setParameter("cursorMessageAt", cursor.messageAt())
                        .setParameter("cursorMessageId", cursor.messageId().toString())
                        .setParameter("batchSize", batchSize);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        List<OrphanCandidate> candidates = rows.stream()
                .map(row -> new OrphanCandidate(toOrphanUuid(row[0]), toOrphanInstant(row[1])))
                .toList();

        log.debug("[SocialMessageRepo] Strona sierot do purge: tenant={}, cutoff={}, cursor={}, zwrócono={}",
                tenantId, cutoff, cursor, candidates.size());
        return candidates;
    }

    /**
     * Usuwa wskazane wiadomości sierocze i zwraca ID FAKTYCZNIE usunięte ({@code DELETE …
     * RETURNING message_id}) — jak {@code EmailMessageRepository#deleteByIds}: pod rolą bez
     * BYPASSRLS DELETE bez polityki usuwa 0 wierszy BEZ błędu (DESIGN §2 U8/R1), więc wołający musi
     * widzieć różnicę między zleconym a potwierdzonym.
     *
     * @param tenantId   UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param messageIds klucze główne wiadomości do usunięcia — pusta kolekcja = pusty wynik
     * @return zbiór {@code message_id} faktycznie usuniętych wierszy (nigdy {@code null})
     * @throws IllegalStateException gdy {@code TenantContext} nie jest ustawiony
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    @Transactional
    public Set<UUID> deleteOrphansByIds(UUID tenantId, Collection<UUID> messageIds) {
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
            List<Object> returned = em.createNativeQuery(DELETE_ORPHANS_BY_IDS_SQL)
                    .setParameter("tenantId", tenantId.toString())
                    .setParameter("messageIds", chunk)
                    .getResultList();

            for (Object id : returned) {
                deleted.add(toOrphanUuid(id));
            }
        }

        log.info("[SocialMessageRepo] Usunięto sieroty: tenant={}, zlecono={}, usunięto={}",
                tenantId, ids.size(), deleted.size());
        return deleted;
    }

    /** Konwersja wartości kolumny {@code uuid} z natywnego zapytania (BE-127). */
    private static UUID toOrphanUuid(Object value) {
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    /** Konwersja wartości kolumny {@code timestamptz} z natywnego zapytania (BE-127). */
    private static Instant toOrphanInstant(Object value) {
        return value instanceof java.sql.Timestamp ts ? ts.toInstant() : (Instant) value;
    }

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera historię wiadomości dla kontaktu (do 50 wiadomości, posortowane od najnowszych).
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @return lista wiadomości posortowanych po {@code sent_at DESC}
     */
    @Transactional(readOnly = true)
    public List<SocialMessage> findByContactId(UUID contactId, UUID tenantId) {
        setTenantContextInDb(tenantId);
        log.debug("[SocialMessageRepo] Pobieranie historii: contactId={}, tenant={}", contactId, tenantId);

        return em.createQuery(
                        "SELECT m FROM SocialMessage m " +
                        "WHERE m.contactId = :contactId AND m.tenantId = :tenantId " +
                        "ORDER BY m.sentAt DESC",
                        SocialMessage.class)
                .setParameter("contactId", contactId)
                .setParameter("tenantId", tenantId)
                .setMaxResults(50)
                .getResultList();
    }

    /**
     * Wyszukuje wiadomość po zewnętrznym identyfikatorze (deduplikacja).
     *
     * @param externalMessageId identyfikator wiadomości na platformie
     * @param tenantId          UUID tenanta
     * @return Optional z wiadomością lub empty gdy brak duplikatu
     */
    @Transactional(readOnly = true)
    public Optional<SocialMessage> findByExternalMessageId(String externalMessageId, UUID tenantId) {
        setTenantContextInDb(tenantId);
        log.debug("[SocialMessageRepo] Sprawdzam idempotentność: externalId={}, tenant={}", externalMessageId, tenantId);

        List<SocialMessage> results = em.createQuery(
                        "SELECT m FROM SocialMessage m " +
                        "WHERE m.externalMessageId = :externalId AND m.tenantId = :tenantId",
                        SocialMessage.class)
                .setParameter("externalId", externalMessageId)
                .setParameter("tenantId", tenantId)
                .setMaxResults(1)
                .getResultList();

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
