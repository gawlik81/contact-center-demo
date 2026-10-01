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
     * SQL zapisu — natywny INSERT (BE-132, EPIC-30). Zastępuje dawne {@code em.merge}, które nie
     * wspiera klucza złożonego {@code (message_id, sent_at)} wymuszonego partycjonowaniem (V100,
     * DB-065) — wzorzec 1:1 z {@code ContactEventRepository#save}.
     *
     * <p><strong>{@code ON CONFLICT ON CONSTRAINT uq_social_message_external_id DO NOTHING RETURNING
     * message_id}</strong> — DRUGA linia obrony idempotentności (pierwsza: dedup aplikacyjny
     * {@code SocialMessageServiceImpl#processIncomingWithTenantContext} →
     * {@link #findByExternalMessageId}), na wypadek race'u między dwoma równoległymi deliveries tego
     * samego zdarzenia webhooka, które OBA przeszły przez dedup aplikacyjny przed commitem. Celowo
     * {@code ON CONFLICT} (zero wyjątków) a NIE {@code catch (DataIntegrityViolationException)}:
     * złapanie wyjątku z natywnego zapytania w TEJ SAMEJ transakcji Springa oznaczyłoby ją jako
     * rollback-only (Hibernate unieważnia sesję po błędzie SQL), więc dalszy kod metody — w tym
     * ewentualny kolejny zapis w tej samej transakcji — i tak by się nie powiódł; {@code ON CONFLICT
     * DO NOTHING} unika tego problemu u źródła, bez wyjątku i bez poświęcania transakcji. Celowo
     * TARGETOWANY na nazwany constraint (nie bezwarunkowe {@code ON CONFLICT DO NOTHING}) — kolizja
     * PRIMARY KEY (praktycznie niemożliwa przy losowym {@code UUID.randomUUID()}) oznaczałaby błąd
     * aplikacji, nie legalny duplikat, i powinna rzucić wyjątek głośno, a nie zostać po cichu
     * wyciszona.
     */
    private static final String INSERT_SQL = """
            INSERT INTO social_message
                (message_id, tenant_id, contact_id, integration_id, platform, direction,
                 external_message_id, sender_external_id, content, attachments,
                 sent_at, received_at, created_at)
            VALUES (
                CAST(:messageId      AS uuid),
                CAST(:tenantId       AS uuid),
                CAST(:contactId      AS uuid),
                CAST(:integrationId  AS uuid),
                CAST(:platform       AS social_platform),
                :direction,
                :externalMessageId,
                :senderExternalId,
                :content,
                CAST(:attachments    AS jsonb),
                :sentAt,
                :receivedAt,
                :createdAt
            )
            ON CONFLICT ON CONSTRAINT uq_social_message_external_id DO NOTHING
            RETURNING message_id
            """;

    /**
     * Zapisuje nową wiadomość social media przez natywny INSERT (BE-132).
     *
     * <p>{@code messageId} jest nadawany PRZED wywołaniem tej metody ({@code UUID.randomUUID()} w
     * {@code SocialMessageServiceImpl}, wzorzec {@code ContactEventServiceImpl#buildEvent}) — gdy
     * encja przychodzi bez {@code messageId}, metoda nadaje go sama (defensywnie, dla wywołań z
     * testów). Domyślne wartości ({@code createdAt}, fallback {@code attachments}) są ustawiane tutaj
     * — zastępuje dawny {@code @PrePersist}, który nie jest wywoływany dla natywnego INSERT.
     *
     * @param message encja do zapisania – {@code tenantId}, {@code platform}, {@code direction},
     *                 {@code externalMessageId}, {@code sentAt} muszą być ustawione
     * @return {@code Optional} z zapisaną encją (z finalnym {@code messageId}) — {@code empty} gdy
     *         INSERT został wyciszony przez {@code ON CONFLICT DO NOTHING} (DRUGA linia obrony
     *         idempotentności, patrz {@link #INSERT_SQL}); wołający MUSI sprawdzić wynik i potraktować
     *         {@code empty} jako idempotentny duplikat, NIE jako błąd
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId niezgodny z kontekstem
     */
    public Optional<SocialMessage> save(SocialMessage message) {
        assertSameTenant(message.getTenantId());
        setTenantContextInDb();

        if (message.getMessageId() == null) {
            message.setMessageId(UUID.randomUUID());
        }
        Instant now = Instant.now();
        if (message.getCreatedAt() == null) {
            message.setCreatedAt(now);
        }
        if (message.getSentAt() == null) {
            message.setSentAt(now);
        }
        if (message.getAttachments() == null) {
            message.setAttachments("[]");
        }

        log.debug("[SocialMessageRepo] Zapisuję wiadomość: messageId={}, externalId={}, platform={}, tenant={}",
                message.getMessageId(), message.getExternalMessageId(), message.getPlatform(), message.getTenantId());

        List<?> returned = em.createNativeQuery(INSERT_SQL)
                .setParameter("messageId", message.getMessageId().toString())
                .setParameter("tenantId", message.getTenantId().toString())
                .setParameter("contactId", message.getContactId() != null ? message.getContactId().toString() : null)
                .setParameter("integrationId", message.getIntegrationId() != null ? message.getIntegrationId().toString() : null)
                .setParameter("platform", message.getPlatform().name())
                .setParameter("direction", message.getDirection())
                .setParameter("externalMessageId", message.getExternalMessageId())
                .setParameter("senderExternalId", message.getSenderExternalId())
                .setParameter("content", message.getContent())
                .setParameter("attachments", message.getAttachments())
                .setParameter("sentAt", message.getSentAt())
                .setParameter("receivedAt", message.getReceivedAt())
                .setParameter("createdAt", message.getCreatedAt())
                .getResultList();

        if (returned.isEmpty()) {
            log.warn("[SocialMessageRepo] Duplikat wykryty przez constraint DB (DRUGA linia obrony — "
                            + "dedup aplikacyjny findByExternalMessageId go nie złapał, prawdopodobny race "
                            + "dwóch równoległych deliveries): externalId={}, tenant={}, sentAt={} — INSERT wyciszony",
                    message.getExternalMessageId(), message.getTenantId(), message.getSentAt());
            return Optional.empty();
        }

        log.debug("[SocialMessageRepo] Wiadomość zapisana: messageId={}", message.getMessageId());
        return Optional.of(message);
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

    // =========================================================================
    // BE-128: Retencja – liczenie wiadomości POWIĄZANYCH z kontaktami kwalifikującymi się (EPIC-30)
    // =========================================================================

    /**
     * SQL — package-private dla testu EXPLAIN. Semi-join po {@code contact_id} do {@code contact}
     * zawężonego TYM SAMYM {@code tenant_id}/{@code cutoff} co reszta kategorii CONTACT_INTERACTIONS
     * — patrz {@code EmailMessageRepository#COUNT_LINKED_TO_ELIGIBLE_CONTACTS_SQL} po pełne
     * uzasadnienie kosztu (analogiczne tutaj: strona {@code social_message} korzysta z
     * {@code idx_social_message_sender} — {@code tenant_id} jako PIERWSZA kolumna, indeks z V010,
     * NIE nowy — gdy tenant jest małym wycinkiem całej tabeli; zweryfikowane EXPLAIN ANALYZE na
     * scratch DB, notatka wykonania BE-128 w {@code TASKS-BACKEND.md}).
     */
    static final String COUNT_LINKED_TO_ELIGIBLE_CONTACTS_SQL = """
            SELECT COUNT(*)
            FROM social_message sm
            WHERE sm.tenant_id = CAST(:tenantId AS uuid)
              AND sm.contact_id IN (
                  SELECT c.contact_id
                  FROM contact c
                  WHERE c.tenant_id = CAST(:tenantId AS uuid)
                    AND c.started_at < :cutoff
              )
            """;

    /**
     * Liczy wiadomości social POWIĄZANE z kontaktem, którego kontakt SAM kwalifikuje się do
     * usunięcia w ramach kategorii CONTACT_INTERACTIONS ({@code contact.started_at < cutoff}, TEN
     * SAM cutoff co reszta kategorii) — dashboard/badge (BE-128), składnik uzupełniający sieroty
     * ({@link #countOrphansOlderThan}).
     *
     * <p>DOKŁADNE liczenie (nie oszacowanie) — patrz {@link #COUNT_LINKED_TO_ELIGIBLE_CONTACTS_SQL}.
     *
     * @param tenantId UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param cutoff   granica czasowa retencji CONTACT_INTERACTIONS — kandydują wiadomości, których
     *                 kontakt ma {@code started_at < cutoff}
     * @return liczba kwalifikujących się wiadomości (nigdy ujemna)
     * @throws IllegalStateException gdy {@code TenantContext} nie jest ustawiony
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    @Transactional(readOnly = true)
    public long countLinkedToContactsOlderThan(UUID tenantId, Instant cutoff) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery(COUNT_LINKED_TO_ELIGIBLE_CONTACTS_SQL)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("cutoff", cutoff)
                .getSingleResult();
        return count.longValue();
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
