package com.contactcenter.domain.email;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
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

    /** Konwersja wartości kolumny {@code timestamptz} z natywnego zapytania (BE-127). */
    private static Instant toInstant(Object value) {
        return value instanceof java.sql.Timestamp ts ? ts.toInstant() : (Instant) value;
    }

    // =========================================================================
    // BE-127: Retencja – sweep wiadomości OSIEROCONYCH (contact_id IS NULL) wg wieku (EPIC-30)
    // =========================================================================

    /**
     * Margines filtra resztkowego: wiadomość zapisana w ostatniej dobie NIGDY nie kwalifikuje się do
     * sweepu sierot, nawet jeśli jej „wiek" ({@link #ORPHAN_AGE_EXPR}) wypada przed {@code cutoff}.
     *
     * <p><strong>Dlaczego to jest potrzebne</strong> (BE-124 §7, doprecyzowanie do DB-059): dla
     * INBOUND „wiek" = INTERNALDATE serwera IMAP ({@code Message#getReceivedDate()}), NIE czas
     * zapisu do bazy. Skrzynka zmigrowana z historycznym INTERNALDATE (albo email z nagłówkiem
     * {@code Date} z przeszłości — mniej istotne, bo INBOUND korzysta z INTERNALDATE, nie z
     * nagłówka) dałaby wiadomość „starą" wg {@code COALESCE(...)}, mimo że dopiero co trafiła do
     * bazy i {@code EmailContactCreator}/{@code EmailRoutingService} może jeszcze nie zdążyć jej
     * przypisać do kontaktu (routing jest asynchroniczny względem zapisu — BE-124 §2 U1). Bez tego
     * filtra taka wiadomość zostałaby usunięta W TRAKCIE routingu.
     *
     * <p>Filtr jest na {@code created_at} (czas zapisu do bazy, zawsze „teraz" w chwili INSERT-u —
     * {@code EmailMessage#onCreate}), NIE na {@link #ORPHAN_AGE_EXPR} — inaczej filtrowałby to samo,
     * co już filtruje {@code cutoff}, i nie chroniłby niczego.
     */
    static final Duration ORPHAN_RESIDUAL_MARGIN = Duration.ofDays(1);

    /**
     * Wyrażenie „wieku wiadomości" — DOKŁADNIE jak w DB-059/V097 (dopasowanie predykatu częściowego
     * indeksu {@code idx_email_message_tenant_orphan_age}, żeby planner faktycznie go użył). Używane
     * identycznie w zapytaniu zliczającym i stronicowanym (patrz SQL-e poniżej).
     */
    private static final String ORPHAN_AGE_EXPR = "COALESCE(received_at, sent_at, created_at)";

    /**
     * SQL dry-run/count — package-private, żeby test integracyjny mógł zrobić {@code EXPLAIN}
     * dokładnie tego zapytania. Budowany z {@link #ORPHAN_AGE_EXPR} (nie skopiowany ręcznie), żeby
     * wyrażenie „wieku" było MECHANICZNIE identyczne w tym zapytaniu, w
     * {@link #FIND_ORPHANS_FIRST_PAGE_SQL}/{@link #FIND_ORPHANS_NEXT_PAGE_SQL} i w predykacie
     * częściowego indeksu {@code idx_email_message_tenant_orphan_age} (DB-059/V097) — literalna
     * zgodność jest wymogiem AC BE-127, nie tylko stylistyczną preferencją.
     */
    static final String COUNT_ORPHANS_SQL = """
            SELECT COUNT(*)
            FROM email_message
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND contact_id IS NULL
              AND %1$s < :cutoff
              AND created_at < :residualCutoff
            """.formatted(ORPHAN_AGE_EXPR);

    /** SQL pierwszej strony sweepu sierot (brak kursora) — package-private dla testu EXPLAIN. */
    static final String FIND_ORPHANS_FIRST_PAGE_SQL = """
            SELECT message_id, %1$s AS message_at, CAST(attachments AS text)
            FROM email_message
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND contact_id IS NULL
              AND %1$s < :cutoff
              AND created_at < :residualCutoff
            ORDER BY %1$s, message_id
            LIMIT :batchSize
            """.formatted(ORPHAN_AGE_EXPR);

    /** SQL kolejnych stron sweepu sierot (kursor keyset) — package-private dla testu EXPLAIN. */
    static final String FIND_ORPHANS_NEXT_PAGE_SQL = """
            SELECT message_id, %1$s AS message_at, CAST(attachments AS text)
            FROM email_message
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND contact_id IS NULL
              AND %1$s < :cutoff
              AND created_at < :residualCutoff
              AND (%1$s, message_id) > (:cursorMessageAt, CAST(:cursorMessageId AS uuid))
            ORDER BY %1$s, message_id
            LIMIT :batchSize
            """.formatted(ORPHAN_AGE_EXPR);

    /**
     * Kandydat sierocy do fazy S3+DELETE ({@link EmailMessageServiceImpl#purgeRows}): pełne dane
     * potrzebne do usunięcia ({@code messageId}, {@code attachmentsJson} — jak {@link AttachmentsRow},
     * ale {@code contactId} jest zawsze {@code null} z definicji sierot) ORAZ wartość „wieku" do
     * zbudowania {@link EmailOrphanCursor} kolejnej strony.
     */
    record OrphanCandidate(UUID messageId, Instant messageAt, String attachmentsJson) {

        /** Konwersja do {@link AttachmentsRow} — wejście {@link EmailMessageServiceImpl#purgeRows}. */
        AttachmentsRow toAttachmentsRow() {
            return new AttachmentsRow(messageId, null, attachmentsJson);
        }
    }

    /**
     * Liczy wiadomości OSIEROCONE ({@code contact_id IS NULL}) starsze niż {@code cutoff} — dry-run
     * (WP-4) i dashboard/badge (BE-128). Kryterium DOKŁADNIE jak {@link #findOrphansOlderThan} (bez
     * paginacji), żeby liczba pokazana administratorowi zgadzała się z tym, co faktycznie usunie
     * kolejny purge.
     *
     * @param tenantId UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param cutoff   granica czasowa — kandydują wiadomości z „wiekiem" < {@code cutoff}
     * @return liczba kwalifikujących się wiadomości (nigdy ujemna)
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
                .setParameter("residualCutoff", Instant.now().minus(ORPHAN_RESIDUAL_MARGIN))
                .getSingleResult();
        return count.longValue();
    }

    /**
     * Zwraca stronę kandydatów sierocych do usunięcia (BE-127) — analogicznie do
     * {@code ContactRepository#findContactIdsOlderThan} (BE-126): NIC nie usuwa, tylko wybiera
     * kandydatów; wywołujący ({@link EmailMessageServiceImpl#purgeOrphansOlderThan}) robi fazę
     * S3+DELETE przez {@link EmailMessageServiceImpl#purgeRows}.
     *
     * <p><strong>Strategia H-1</strong> (head-of-line blocking, jak w BE-126): porządek
     * {@code ORDER BY <wiek>, message_id} jest deterministyczny i wspiera stronicowanie keyset —
     * strona zaczyna się ŚCIŚLE PO ostatnim kandydacie poprzedniej strony, niezależnie od tego, czy
     * jego wiadomość została faktycznie usunięta (porażka S3). Terminacja pętli wołającego zależy
     * wyłącznie od wyczerpania kandydatów ({@code page.size() < batchSize}), nie od liczby usunięć.
     *
     * <p>Filtr resztkowy {@code created_at < :residualCutoff} (patrz {@link #ORPHAN_RESIDUAL_MARGIN})
     * jest {@code Filter}, nie {@code Index Cond} — nie zmienia planu (indeks z DB-059 nadal
     * używany), tylko zawęża wynik.
     *
     * <p>Brak indeksu pokrywającego {@code (tenant_id, wiek, message_id)} bez dodatkowego filtra —
     * identyczna sytuacja jak {@code ContactRepository#findContactIdsOlderThan} (indeks z DB-059 nie
     * niesie {@code message_id}, więc porządek/tie-break dogrywa planner).
     *
     * @param tenantId  UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param cutoff    granica czasowa — kandydują wiadomości z „wiekiem" < {@code cutoff}
     * @param cursor    ostatni kandydat poprzedniej strony ({@code null} dla pierwszej strony)
     * @param batchSize maksymalna liczba kandydatów na stronę
     * @return strona kandydatów posortowana rosnąco po {@code (wiek, message_id)} — pusta, gdy nie ma
     *         więcej kandydatów; {@code page.size() < batchSize} sygnalizuje ostatnią stronę
     * @throws IllegalStateException gdy {@code TenantContext} nie jest ustawiony
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    @Transactional(readOnly = true)
    public List<OrphanCandidate> findOrphansOlderThan(
            UUID tenantId, Instant cutoff, EmailOrphanCursor cursor, int batchSize) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        Instant residualCutoff = Instant.now().minus(ORPHAN_RESIDUAL_MARGIN);
        var query = cursor == null
                ? em.createNativeQuery(FIND_ORPHANS_FIRST_PAGE_SQL)
                        .setParameter("tenantId", tenantId.toString())
                        .setParameter("cutoff", cutoff)
                        .setParameter("residualCutoff", residualCutoff)
                        .setParameter("batchSize", batchSize)
                : em.createNativeQuery(FIND_ORPHANS_NEXT_PAGE_SQL)
                        .setParameter("tenantId", tenantId.toString())
                        .setParameter("cutoff", cutoff)
                        .setParameter("residualCutoff", residualCutoff)
                        .setParameter("cursorMessageAt", cursor.messageAt())
                        .setParameter("cursorMessageId", cursor.messageId().toString())
                        .setParameter("batchSize", batchSize);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        List<OrphanCandidate> candidates = rows.stream()
                .map(row -> new OrphanCandidate(toUuid(row[0]), toInstant(row[1]), (String) row[2]))
                .toList();

        log.debug("[EmailMessageRepo] Strona sierot do purge: tenant={}, cutoff={}, cursor={}, zwrócono={}",
                tenantId, cutoff, cursor, candidates.size());
        return candidates;
    }

    // =========================================================================
    // BE-128: Retencja – liczenie wiadomości POWIĄZANYCH z kontaktami kwalifikującymi się (EPIC-30)
    // =========================================================================

    /**
     * SQL — package-private dla testu EXPLAIN. Semi-join po {@code contact_id} do {@code contact}
     * zawężonego TYM SAMYM {@code tenant_id}/{@code cutoff} co reszta kategorii CONTACT_INTERACTIONS
     * ({@code started_at < cutoff} — identyczne kryterium jak {@code ContactRepository
     * #findContactIdsOlderThan}/{@code #deleteBatchOlderThan}).
     *
     * <p><strong>Koszt (zweryfikowany EXPLAIN ANALYZE na scratch DB, notatka wykonania BE-128 w
     * {@code TASKS-BACKEND.md}):</strong> podzapytanie na {@code contact} korzysta z partition
     * pruning ({@code started_at} jest kolumną partycjonowania) i {@code idx_contact_tenant_started_at}.
     * Strona {@code email_message} — przy realistycznej wielotenantowej selektywności (jeden tenant
     * = mały wycinek całej tabeli, zweryfikowane przy ~10% udziału na 1,16 mln wierszy w 10 tenantach)
     * planner wybiera {@code Bitmap Index Scan} na {@code uq_email_message_id_header} ({@code
     * tenant_id} jako PIERWSZA kolumna tego unikalnego indeksu z V010 — NIE nowy indeks, żadna
     * migracja SQL nie jest potrzebna), więc koszt jest ograniczony do wierszy TEGO tenanta, a NIE
     * do rozmiaru całej tabeli (czyli NIE rośnie z liczbą INNYCH tenantów na platformie). Gdy tenant
     * jest większością tabeli, planner naturalnie wybiera Seq Scan — wtedy jest to i tak najszybsza
     * opcja (skan ≈ skan danych samego tenanta).
     */
    static final String COUNT_LINKED_TO_ELIGIBLE_CONTACTS_SQL = """
            SELECT COUNT(*)
            FROM email_message em
            WHERE em.tenant_id = CAST(:tenantId AS uuid)
              AND em.contact_id IN (
                  SELECT c.contact_id
                  FROM contact c
                  WHERE c.tenant_id = CAST(:tenantId AS uuid)
                    AND c.started_at < :cutoff
              )
            """;

    /**
     * Liczy wiadomości e-mail POWIĄZANE z kontaktem, którego kontakt SAM kwalifikuje się do
     * usunięcia w ramach kategorii CONTACT_INTERACTIONS ({@code contact.started_at < cutoff}, TEN
     * SAM cutoff co reszta kategorii) — dashboard/badge (BE-128), składnik uzupełniający sieroty
     * ({@link #countOrphansOlderThan}).
     *
     * <p>DOKŁADNE liczenie (nie oszacowanie) — patrz {@link #COUNT_LINKED_TO_ELIGIBLE_CONTACTS_SQL}
     * po uzasadnienie kosztu. Wynik jest wiarygodnym ORIENTACYJNYM licznikiem dla administratora —
     * może się różnić o kilka wierszy od tego, co faktycznie usunie kolejny przebieg purge (BE-126),
     * który operuje na stronicowanej liście kandydatów w konkretnym momencie przy współbieżnym ruchu.
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
