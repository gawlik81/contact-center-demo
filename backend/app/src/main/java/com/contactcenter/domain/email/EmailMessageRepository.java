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
import java.time.temporal.ChronoUnit;
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
     * SQL zapisu — natywny INSERT (BE-134, EPIC-30). Zastępuje dawne {@code em.merge}, które nie
     * wspiera klucza złożonego {@code (message_id, message_at)} wymuszonego partycjonowaniem
     * (V102, DB-067) — wzorzec 1:1 z {@code SocialMessageRepository#INSERT_SQL} (BE-132).
     *
     * <p><strong>Deduplikacja nagłówka Message-ID — dlaczego {@code WHERE NOT EXISTS}, a NIE
     * {@code ON CONFLICT}:</strong> constraint {@code uq_email_message_id_header} jest
     * {@code DEFERRABLE INITIALLY DEFERRED} (V102), a PostgreSQL odrzuca {@code ON CONFLICT ON
     * CONSTRAINT} wskazujący constraint odroczalny („ON CONFLICT does not support deferrable unique
     * constraints/exclusion constraints as arbiters"). Dlatego duplikat wykrywa {@code NOT EXISTS} po
     * {@code (tenant_id, message_id_header)} — ten sam klucz, co dedup aplikacyjny
     * {@link #findByMessageIdHeader} (D4 = A: dedup bez daty). Wyścig dwóch równoległych zapisów tego
     * samego nagłówka rozstrzyga {@link #ADVISORY_LOCK_SQL} (lock transakcyjny per tenant+nagłówek),
     * więc drugi INSERT widzi już zatwierdzony wiersz i nie dochodzi do błędu odroczonego
     * constraintu w COMMIT. Brak {@code catch DataIntegrityViolationException} w transakcji
     * (BE-132: złapany wyjątek z natywnego zapytania oznaczyłby transakcję jako rollback-only).
     *
     * <p>Wszystkie parametry w {@code SELECT} są jawnie rzutowane: w {@code INSERT … SELECT} typ
     * {@code unknown} zostałby zresolwowany do {@code text} i nie przypisałby się do kolumny
     * {@code timestamptz}/{@code uuid}/{@code jsonb}.
     */
    static final String INSERT_SQL = """
            INSERT INTO email_message
                (message_id, tenant_id, contact_id, direction, from_address, to_address, cc_address,
                 bcc_address, subject, body_html, body_text, message_id_header, in_reply_to, attachments,
                 received_at, sent_at, delivery_status, created_at, message_at)
            SELECT CAST(:messageId AS uuid), CAST(:tenantId AS uuid), CAST(:contactId AS uuid),
                   CAST(:direction AS varchar), CAST(:fromAddress AS varchar), CAST(:toAddress AS text),
                   CAST(:ccAddress AS text), CAST(:bccAddress AS text), CAST(:subject AS varchar),
                   CAST(:bodyHtml AS text), CAST(:bodyText AS text), CAST(:messageIdHeader AS varchar),
                   CAST(:inReplyTo AS varchar), CAST(:attachments AS jsonb),
                   CAST(:receivedAt AS timestamptz), CAST(:sentAt AS timestamptz),
                   CAST(:deliveryStatus AS varchar), CAST(:createdAt AS timestamptz),
                   CAST(:messageAt AS timestamptz)
            WHERE NOT EXISTS (
                SELECT 1 FROM email_message e
                WHERE e.tenant_id = CAST(:tenantId AS uuid)
                  AND e.message_id_header = CAST(:messageIdHeader AS varchar)
            )
            RETURNING message_id
            """;

    /**
     * Transakcyjny advisory lock na parę (tenant, nagłówek Message-ID) — serializuje równoległe
     * INSERT-y tego samego nagłówka, żeby {@link #INSERT_SQL} nie przepuścił duplikatu (patrz Javadoc
     * {@link #INSERT_SQL}). Zwalniany automatycznie na COMMIT/ROLLBACK. Klucz blokady to hash, więc
     * kolizja dwóch różnych nagłówków tylko je serializuje — poprawności to nie narusza.
     */
    static final String ADVISORY_LOCK_SQL =
            "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))";

    /**
     * SQL aktualizacji — natywny UPDATE po pełnym kluczu {@code (message_id, message_at)} (BE-134).
     * Zapisuje wszystkie kolumny mutowalne (tak jak dawny {@code em.merge}); {@code tenant_id},
     * {@code created_at} i {@code message_at} NIE są zapisywane (niemodyfikowalne). Wskazanie
     * {@code message_at} w WHERE pozwala PostgreSQL przyciąć wyszukiwanie do jednej partycji.
     */
    static final String UPDATE_SQL = """
            UPDATE email_message SET
                contact_id        = CAST(:contactId AS uuid),
                direction         = CAST(:direction AS varchar),
                from_address      = CAST(:fromAddress AS varchar),
                to_address        = CAST(:toAddress AS text),
                cc_address        = CAST(:ccAddress AS text),
                bcc_address       = CAST(:bccAddress AS text),
                subject           = CAST(:subject AS varchar),
                body_html         = CAST(:bodyHtml AS text),
                body_text         = CAST(:bodyText AS text),
                message_id_header = CAST(:messageIdHeader AS varchar),
                in_reply_to       = CAST(:inReplyTo AS varchar),
                attachments       = CAST(:attachments AS jsonb),
                received_at       = CAST(:receivedAt AS timestamptz),
                sent_at           = CAST(:sentAt AS timestamptz),
                delivery_status   = CAST(:deliveryStatus AS varchar)
            WHERE tenant_id  = CAST(:tenantId AS uuid)
              AND message_id = CAST(:messageId AS uuid)
              AND message_at = CAST(:messageAt AS timestamptz)
            """;

    /**
     * Zapisuje nową wiadomość email natywnym INSERT-em (BE-134).
     *
     * <p>Przed zapisem: {@code messageId} nadawany w Java, gdy brak ({@code UUID.randomUUID()});
     * {@code messageAt} MUSI być ustawione przez wołającego (INBOUND = INTERNALDATE, OUTBOUND = czas
     * wysłania) — brak wartości to błąd programisty, a NIE cicha podmiana na {@code now()} (dlatego
     * DEFAULT z V101 nie jest tu używany). {@code createdAt} i {@code attachments} dostają wartości
     * domyślne (zastępują {@code @PrePersist}, którego natywny INSERT nie wywołuje).
     *
     * @param message encja do zapisania — {@code tenantId}, {@code messageAt} i pola NOT NULL muszą być ustawione
     * @return {@code Optional} z zapisaną encją — {@code empty} gdy wiadomość o tym samym
     *         {@code message_id_header} już istnieje w tenancie (duplikat; wołający traktuje to jako
     *         idempotentny no-op, NIE jako błąd)
     * @throws IllegalArgumentException gdy {@code messageAt} jest null albo ma precyzję wyższą niż mikrosekundy
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId niezgodny z kontekstem
     */
    public Optional<EmailMessage> save(EmailMessage message) {
        assertSameTenant(message.getTenantId());
        setTenantContextInDb();
        requireStorableMessageAt(message.getMessageAt());

        if (message.getId() == null) {
            message.setId(UUID.randomUUID());
        }
        if (message.getCreatedAt() == null) {
            message.setCreatedAt(Instant.now());
        }
        if (message.getAttachments() == null) {
            message.setAttachments("[]");
        }

        if (message.getMessageIdHeader() != null) {
            em.createNativeQuery(ADVISORY_LOCK_SQL)
                    .setParameter("lockKey", message.getTenantId() + "|" + message.getMessageIdHeader())
                    .getSingleResult();
        }

        log.debug("[EmailMessageRepo] Zapisuję wiadomość: messageId={}, messageAt={}, direction={}, tenant={}",
                message.getId(), message.getMessageAt(), message.getDirection(), message.getTenantId());

        List<?> returned = em.createNativeQuery(INSERT_SQL)
                .setParameter("messageId", message.getId().toString())
                .setParameter("tenantId", message.getTenantId().toString())
                .setParameter("contactId", toStringOrNull(message.getContactId()))
                .setParameter("direction", message.getDirection())
                .setParameter("fromAddress", message.getFromAddress())
                .setParameter("toAddress", message.getToAddress())
                .setParameter("ccAddress", message.getCcAddress())
                .setParameter("bccAddress", message.getBccAddress())
                .setParameter("subject", message.getSubject())
                .setParameter("bodyHtml", message.getBodyHtml())
                .setParameter("bodyText", message.getBodyText())
                .setParameter("messageIdHeader", message.getMessageIdHeader())
                .setParameter("inReplyTo", message.getInReplyTo())
                .setParameter("attachments", message.getAttachments())
                .setParameter("receivedAt", message.getReceivedAt())
                .setParameter("sentAt", message.getSentAt())
                .setParameter("deliveryStatus", message.getDeliveryStatus())
                .setParameter("createdAt", message.getCreatedAt())
                .setParameter("messageAt", message.getMessageAt())
                .getResultList();

        if (returned.isEmpty()) {
            log.info("[EmailMessageRepo] Duplikat nagłówka Message-ID — INSERT pominięty: tenant={}, header={}",
                    message.getTenantId(), message.getMessageIdHeader());
            return Optional.empty();
        }

        log.debug("[EmailMessageRepo] Wiadomość zapisana: messageId={}, messageAt={}",
                message.getId(), message.getMessageAt());
        return Optional.of(message);
    }

    /**
     * Aktualizuje istniejącą wiadomość email natywnym UPDATE-em po pełnym kluczu (BE-134).
     *
     * <p>Wiersz identyfikowany przez {@code (message_id, message_at)} — {@code messageAt} z encji, który
     * jest niemodyfikowalny, więc zawsze wskazuje ten sam wiersz co przy zapisie.
     *
     * @param message encja do aktualizacji (pochodząca z {@link #findById(UUID, Instant)} lub z {@link #save})
     * @return ta sama encja (po zapisie)
     * @throws IllegalStateException gdy żaden wiersz nie został zaktualizowany (brak wiersza albo RLS
     *                               ukrywa go przed bieżącym tenantem — zapis NIE może się cicho nie powieść)
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId niezgodny z kontekstem
     */
    public EmailMessage update(EmailMessage message) {
        assertSameTenant(message.getTenantId(), message.getId());
        setTenantContextInDb();
        if (message.getMessageAt() == null) {
            throw new IllegalArgumentException("EmailMessage.messageAt jest wymagane do UPDATE (klucz partycji)");
        }

        int updated = em.createNativeQuery(UPDATE_SQL)
                .setParameter("tenantId", message.getTenantId().toString())
                .setParameter("messageId", message.getId().toString())
                .setParameter("messageAt", message.getMessageAt())
                .setParameter("contactId", toStringOrNull(message.getContactId()))
                .setParameter("direction", message.getDirection())
                .setParameter("fromAddress", message.getFromAddress())
                .setParameter("toAddress", message.getToAddress())
                .setParameter("ccAddress", message.getCcAddress())
                .setParameter("bccAddress", message.getBccAddress())
                .setParameter("subject", message.getSubject())
                .setParameter("bodyHtml", message.getBodyHtml())
                .setParameter("bodyText", message.getBodyText())
                .setParameter("messageIdHeader", message.getMessageIdHeader())
                .setParameter("inReplyTo", message.getInReplyTo())
                .setParameter("attachments", message.getAttachments() != null ? message.getAttachments() : "[]")
                .setParameter("receivedAt", message.getReceivedAt())
                .setParameter("sentAt", message.getSentAt())
                .setParameter("deliveryStatus", message.getDeliveryStatus())
                .executeUpdate();

        if (updated != 1) {
            throw new IllegalStateException("email_message: UPDATE dotknął " + updated + " wierszy (oczekiwano 1): "
                    + "messageId=" + message.getId() + ", messageAt=" + message.getMessageAt()
                    + " — brak wiersza albo ukryty przez RLS");
        }
        return message;
    }

    /**
     * Wymusza kontrakt {@link #save}: {@code messageAt} ustawione i o precyzji mikrosekund.
     *
     * <p>PostgreSQL przechowuje {@code timestamptz} z dokładnością do mikrosekund. Wartość z
     * nanosekundami zapisana w bazie wróciłaby zaokrąglona, a późniejszy lookup/UPDATE po kluczu
     * {@code message_at} nie trafiałby w wiersz (albo event RabbitMQ niósłby inną wartość niż baza).
     * Dlatego źródła {@code messageAt} MUSZĄ obcinać do mikrosekund ({@code truncatedTo(MICROS)}).
     */
    static void requireStorableMessageAt(Instant messageAt) {
        if (messageAt == null) {
            throw new IllegalArgumentException("EmailMessage.messageAt jest wymagane (klucz partycji, DB-067)");
        }
        if (!messageAt.equals(messageAt.truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalArgumentException(
                    "EmailMessage.messageAt ma precyzję wyższą niż mikrosekundy (PostgreSQL): " + messageAt);
        }
    }

    private static String toStringOrNull(UUID value) {
        return value != null ? value.toString() : null;
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

    /**
     * SQL fazy 1 — package-private, żeby test integracyjny mógł zrobić {@code EXPLAIN} dokładnie tego zapytania.
     * Projekcja zawiera {@code message_at} (BE-134): faza 3 usuwa wiersze pełnym kluczem złożonym.
     */
    static final String FIND_ATTACHMENTS_SQL = """
            SELECT message_id, message_at, contact_id, CAST(attachments AS text)
            FROM email_message
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND contact_id IN (:contactIds)
            """;

    /**
     * SQL fazy 3 — package-private z tego samego powodu co {@link #FIND_ATTACHMENTS_SQL}.
     *
     * <p>BE-134: wiersze identyfikowane PEŁNYM kluczem {@code (message_id, message_at)}. Parametr
     * {@code :rows} to JSON {@code [{"message_id": "...", "message_at": "..."}]} zbudowany w Java
     * ({@link #toKeyJson}); {@code jsonb_to_recordset} daje typowane kolumny, więc SQL pozostaje
     * stały (jeden plan, bez dynamicznego doklejania placeholderów). Warunek {@code message_at} pozwala
     * PostgreSQL skierować każdy wiersz do właściwej partycji. NIGDY {@code ctid} (DB-067: nie jest
     * unikalny między partycjami — zob. {@code ContactRepository#deleteBatchOlderThan}).
     */
    static final String DELETE_BY_IDS_SQL = """
            DELETE FROM email_message em
            USING jsonb_to_recordset(CAST(:rows AS jsonb)) AS k(message_id uuid, message_at timestamptz)
            WHERE em.tenant_id = CAST(:tenantId AS uuid)
              AND em.message_id = k.message_id
              AND em.message_at = k.message_at
            RETURNING em.message_id, em.message_at
            """;

    /**
     * Wiersz do fazy S3 purge: pełny klucz wiadomości ({@code messageId}, {@code messageAt}), kontakt
     * (do {@code contactIdsBlocked}; może być {@code null} dla wiadomości osieroconych — BE-127) oraz
     * surowy JSONB {@code attachments} jako tekst.
     *
     * <p>BE-134: rekord niesie {@code messageAt}, bo po partycjonowaniu (DB-067) PK jest złożony i
     * {@link #deleteByIds} identyfikuje wiersze pełnym kluczem (wzorzec
     * {@code ContactRepository#deleteBatchOlderThan}).
     */
    record AttachmentsRow(UUID messageId, Instant messageAt, UUID contactId, String attachmentsJson) {

        /** Pełny klucz wiadomości — do mapy „zlecone do usunięcia" i porównania z potwierdzeniem RETURNING. */
        EmailMessageId key() {
            return new EmailMessageId(messageId, messageAt);
        }
    }

    /**
     * Faza 1 purge „S3 przed wierszem": zwraca wiadomości tenanta powiązane z podanymi kontaktami
     * razem z metadanymi załączników — bez usuwania czegokolwiek.
     *
     * <p><strong>Natywny SQL z projekcją skalarną</strong> ({@code Object[]}), nie
     * {@code createNativeQuery(sql, EmailMessage.class)} — w tym projekcie natywne zapytania z
     * {@code resultClass} i kolumnami enum kończyły się {@code ClassCastException} (EPIC-29), a do
     * fazy S3 potrzebne są tylko cztery kolumny. JSONB jest rzutowany na tekst po stronie SQL;
     * parsowanie i odporność na uszkodzone dane: {@code EmailAttachmentKeys#extractS3Keys}.
     *
     * <p><strong>Indeks:</strong> {@code contact_id IN (...)} korzysta z
     * {@code idx_email_message_contact (contact_id, received_at DESC)}; filtr {@code tenant_id}
     * to dodatkowa bariera izolacji (obok RLS). Zapytanie po {@code contact_id} bez klucza
     * partycji działa też na tabeli partycjonowanej (DB-067) — PostgreSQL odpyta indeksy
     * poszczególnych partycji (koszt: liczba partycji).
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
     * Faza 3 purge „S3 przed wierszem": usuwa wskazane wiadomości tenanta (pełny klucz
     * {@code (message_id, message_at)}) i zwraca klucze FAKTYCZNIE usunięte ({@code DELETE … RETURNING}).
     *
     * <p>Wywołujący przekazuje wyłącznie wiadomości, których wszystkie obiekty S3 zostały już
     * usunięte. Filtr {@code tenant_id} zostaje jako bariera izolacji.
     *
     * <p>Zwracanie faktycznie usuniętych kluczy (a nie liczby zleconych) jest istotne: pod rolą bez
     * BYPASSRLS DELETE bez polityki {@code FOR DELETE} usuwa 0 wierszy BEZ błędu (DESIGN §2 U8,
     * R1) — wołający zobaczy różnicę i nie usunie kontaktu, którego wiadomości zostały.
     *
     * @param tenantId UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param keys     pełne klucze wiadomości do usunięcia — pusta kolekcja = pusty wynik
     * @return zbiór kluczy FAKTYCZNIE usuniętych wierszy (nigdy {@code null})
     * @throws IllegalStateException gdy {@code TenantContext} nie jest ustawiony
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    @Transactional
    public Set<EmailMessageId> deleteByIds(UUID tenantId, Collection<EmailMessageId> keys) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        Set<EmailMessageId> deleted = new HashSet<>();
        if (keys == null || keys.isEmpty()) {
            return deleted;
        }

        List<EmailMessageId> all = List.copyOf(keys);
        for (int from = 0; from < all.size(); from += IN_LIST_CHUNK_SIZE) {
            List<EmailMessageId> chunk = all.subList(from, Math.min(from + IN_LIST_CHUNK_SIZE, all.size()));

            @SuppressWarnings("unchecked")
            List<Object[]> returned = em.createNativeQuery(DELETE_BY_IDS_SQL)
                    .setParameter("tenantId", tenantId.toString())
                    .setParameter("rows", toKeyJson(chunk))
                    .getResultList();

            for (Object[] row : returned) {
                deleted.add(new EmailMessageId(toUuid(row[0]), toInstant(row[1])));
            }
        }

        log.info("[EmailMessageRepo] Usunięto wiadomości: tenant={}, zlecono={}, usunięto={}",
                tenantId, all.size(), deleted.size());
        return deleted;
    }

    /**
     * Buduje parametr JSON dla {@link #DELETE_BY_IDS_SQL}: {@code [{"message_id":"…","message_at":"…"}]}.
     * Wartości pochodzą wyłącznie z UUID i {@code Instant.toString()} (ISO-8601 bez znaków wymagających
     * escapowania), więc nie ma tu wejścia od użytkownika do sklejenia.
     */
    static String toKeyJson(Collection<EmailMessageId> keys) {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (EmailMessageId key : keys) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"message_id\":\"").append(key.getId())
                    .append("\",\"message_at\":\"").append(key.getMessageAt()).append("\"}");
        }
        return json.append(']').toString();
    }

    /**
     * Mapuje wiersz projekcji {@link #FIND_ATTACHMENTS_SQL} ({@code message_id, message_at, contact_id,
     * CAST(attachments AS text)}) na {@link AttachmentsRow}. {@code contact_id} bywa {@code NULL}
     * (wiadomość osierocona — BE-127 użyje tego samego mapowania) i wtedy trafia do rekordu jako
     * {@code null}. Package-private, żeby test jednostkowy pokrył ścieżkę {@code NULL} bez bazy.
     */
    static AttachmentsRow toAttachmentsRow(Object[] row) {
        return new AttachmentsRow(toUuid(row[0]), toInstant(row[1]), toUuid(row[2]), (String) row[3]);
    }

    /** Konwersja wartości kolumny {@code uuid} z natywnego zapytania; {@code null} (kolumna NULL) → {@code null}. */
    private static UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof UUID uuid ? uuid : UUID.fromString(String.valueOf(value));
    }

    /**
     * Konwersja wartości kolumny {@code timestamptz} z natywnego zapytania. Sterownik może zwrócić
     * {@code java.sql.Timestamp}, {@code Instant} albo {@code OffsetDateTime} — wszystkie trzy obsługiwane.
     */
    static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        return (Instant) value;
    }

    // =========================================================================
    // BE-127: Retencja – sweep wiadomości OSIEROCONYCH (contact_id IS NULL) wg wieku (EPIC-30)
    // =========================================================================

    /**
     * Margines filtra resztkowego: wiadomość zapisana w ostatniej dobie NIGDY nie kwalifikuje się do
     * sweepu sierot, nawet jeśli jej {@code message_at} wypada przed {@code cutoff}.
     *
     * <p><strong>Dlaczego to jest potrzebne</strong> (BE-124 §7, doprecyzowanie do DB-059): dla
     * INBOUND {@code message_at} = INTERNALDATE serwera IMAP ({@code Message#getReceivedDate()}), NIE
     * czas zapisu do bazy. Skrzynka zmigrowana z historycznym INTERNALDATE dałaby wiadomość „starą"
     * mimo że dopiero co trafiła do bazy, a {@code EmailContactCreator}/{@code EmailRoutingService}
     * mogą jeszcze nie zdążyć jej przypisać do kontaktu (routing jest asynchroniczny względem zapisu —
     * BE-124 §2 U1). Bez tego filtra taka wiadomość zostałaby usunięta W TRAKCIE routingu.
     *
     * <p>Filtr jest na {@code created_at} (czas zapisu do bazy, zawsze „teraz" w chwili INSERT-u),
     * NIE na {@code message_at} — inaczej filtrowałby to samo, co już filtruje {@code cutoff}.
     */
    static final Duration ORPHAN_RESIDUAL_MARGIN = Duration.ofDays(1);

    /**
     * Kolumna „wieku wiadomości" dla sweepu sierot — BE-134 (DB-067/V102).
     *
     * <p>Do BE-134 wiek liczono wyrażeniem {@code COALESCE(received_at, sent_at, created_at)} (DB-059).
     * Po partycjonowaniu kolumna {@code message_at} NOT NULL jest tym samym czasem (backfill V101 =
     * {@code COALESCE(...)}, a nowe wiersze ustawiają ją jawnie), więc zapytania używają jej wprost.
     * Efekt: predykat {@code message_at < :cutoff} jest zgodny z częściowym indeksem
     * {@code idx_email_message_tenant_orphan_age ON (tenant_id, message_at) WHERE contact_id IS NULL}
     * (V102). Wyrażenie {@code COALESCE} z poprzedniej wersji NIE pasowałoby do tego indeksu i planner
     * robiłby Seq Scan (zmierzone na scratch, notatka DB-067).
     */
    static final String ORPHAN_AGE_COLUMN = "message_at";

    /**
     * SQL dry-run/count — package-private, żeby test integracyjny mógł zrobić {@code EXPLAIN}
     * dokładnie tego zapytania (predykat zgodny z częściowym indeksem {@code idx_email_message_tenant_orphan_age}).
     */
    static final String COUNT_ORPHANS_SQL = """
            SELECT COUNT(*)
            FROM email_message
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND contact_id IS NULL
              AND message_at < :cutoff
              AND created_at < :residualCutoff
            """;

    /** SQL pierwszej strony sweepu sierot (brak kursora) — package-private dla testu EXPLAIN. */
    static final String FIND_ORPHANS_FIRST_PAGE_SQL = """
            SELECT message_id, message_at, CAST(attachments AS text)
            FROM email_message
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND contact_id IS NULL
              AND message_at < :cutoff
              AND created_at < :residualCutoff
            ORDER BY message_at, message_id
            LIMIT :batchSize
            """;

    /** SQL kolejnych stron sweepu sierot (kursor keyset) — package-private dla testu EXPLAIN. */
    static final String FIND_ORPHANS_NEXT_PAGE_SQL = """
            SELECT message_id, message_at, CAST(attachments AS text)
            FROM email_message
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND contact_id IS NULL
              AND message_at < :cutoff
              AND created_at < :residualCutoff
              AND (message_at, message_id) > (:cursorMessageAt, CAST(:cursorMessageId AS uuid))
            ORDER BY message_at, message_id
            LIMIT :batchSize
            """;

    /**
     * Kandydat sierocy do fazy S3+DELETE ({@link EmailMessageServiceImpl#purgeRows}): pełny klucz
     * ({@code messageId}, {@code messageAt}) i {@code attachmentsJson} — jak {@link AttachmentsRow}, ale
     * {@code contactId} jest zawsze {@code null} z definicji sierot. {@code messageAt} służy też do
     * zbudowania {@link EmailOrphanCursor} kolejnej strony.
     */
    record OrphanCandidate(UUID messageId, Instant messageAt, String attachmentsJson) {

        /** Konwersja do {@link AttachmentsRow} — wejście {@link EmailMessageServiceImpl#purgeRows}. */
        AttachmentsRow toAttachmentsRow() {
            return new AttachmentsRow(messageId, messageAt, null, attachmentsJson);
        }
    }

    /**
     * Liczy wiadomości OSIEROCONE ({@code contact_id IS NULL}) starsze niż {@code cutoff} — dry-run
     * (WP-4) i dashboard/badge (BE-128). Kryterium DOKŁADNIE jak {@link #findOrphansOlderThan} (bez
     * paginacji), żeby liczba pokazana administratorowi zgadzała się z tym, co faktycznie usunie
     * kolejny purge.
     *
     * @param tenantId UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param cutoff   granica czasowa — kandydują wiadomości z {@code message_at} < {@code cutoff}
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
     * {@code ORDER BY message_at, message_id} jest deterministyczny i wspiera stronicowanie keyset —
     * strona zaczyna się ŚCIŚLE PO ostatnim kandydacie poprzedniej strony, niezależnie od tego, czy
     * jego wiadomość została faktycznie usunięta (porażka S3). Terminacja pętli wołającego zależy
     * wyłącznie od wyczerpania kandydatów ({@code page.size() < batchSize}), nie od liczby usunięć.
     *
     * <p>Filtr resztkowy {@code created_at < :residualCutoff} (patrz {@link #ORPHAN_RESIDUAL_MARGIN})
     * jest {@code Filter}, nie {@code Index Cond} — nie zmienia planu (indeks z DB-059/V102 nadal
     * używany), tylko zawęża wynik.
     *
     * @param tenantId  UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param cutoff    granica czasowa — kandydują wiadomości z {@code message_at} < {@code cutoff}
     * @param cursor    kursor poprzedniej strony ({@code null} dla pierwszej strony)
     * @param batchSize maksymalna liczba kandydatów na stronę
     * @return strona kandydatów posortowana rosnąco po {@code (message_at, message_id)} — pusta, gdy nie ma
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
    // BE-131: Retencja – sweep porzuconych załączników pending w S3 (EPIC-30)
    // =========================================================================

    /**
     * SQL — package-private dla testu EXPLAIN (BE-131). Pre-filtr {@code attachments <> '[]'} +
     * {@code LIKE '%/pending/%'} jest poprawnościowo bezpieczny (nie tylko optymalizacją): klucz
     * {@code pending/{uuid}/...} to JEDYNY schemat kluczy zawierający segment {@code pending}
     * ({@code EmailAttachmentKeys#inboundKey} ma w tym miejscu {@code messageId}, zawsze UUID, nigdy
     * literału {@code pending}) — żadna wiadomość odwołująca się do klucza {@code pending/} nie
     * zostanie przez ten filtr pominięta. Parsowanie faktycznych kluczy (odporne na uszkodzone
     * dane) robi {@link EmailAttachmentKeys#extractS3Keys} w Javie, nie SQL.
     */
    static final String FIND_ATTACHMENTS_REFERENCING_PENDING_SQL = """
            SELECT message_id, CAST(attachments AS text)
            FROM email_message
            WHERE tenant_id = CAST(:tenantId AS uuid)
              AND attachments <> '[]'
              AND CAST(attachments AS text) LIKE '%/pending/%'
            """;

    /**
     * Zwraca zbiór kluczy S3 spod {@code email-attachments/{tenantId}/pending/...} wciąż
     * odwoływanych przez którąkolwiek wiadomość tenanta ({@code attachments[*].s3_key}) — sweep
     * porzuconych załączników pending (retencja EPIC-30, BE-131, {@code PendingAttachmentSweepJob}).
     *
     * <p>Wiadomości OUTBOUND odwołują się do kluczy {@code pending/} BEZ przenoszenia obiektu
     * (korekta BE-124/BE-131: pierwotne założenie „{@code pending/} = tymczasowe" było fałszywe) —
     * klucz obecny w zwróconym zbiorze NIE jest porzucony, nawet jeśli jego {@code LastModified} w
     * S3 jest starszy niż TTL sweepu, i NIE WOLNO go usunąć.
     *
     * <p>Pobiera cały zbiór referencji tenanta JEDNYM zapytaniem (wariant z ticketu BE-131: „zbiór
     * {@code attachments[*].s3_key} pobrany raz"), nie po jednym zapytaniu na kandydata S3 — liczba
     * obiektów {@code pending/} do sprawdzenia w jednym przebiegu jest rzędu dziesiątek/setek, nie
     * warto płacić N zapytań JSONB containment bez indeksu GIN (odłożone jako przyszłe usprawnienie
     * przy wolumenie, patrz TASKS-BACKEND.md BE-131).
     *
     * @param tenantId UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @return zbiór kluczy S3 (nigdy {@code null}), pusty gdy żadna wiadomość nie odwołuje się do {@code pending/}
     * @throws IllegalStateException gdy {@code TenantContext} nie jest ustawiony
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    @Transactional(readOnly = true)
    public Set<String> findReferencedPendingS3Keys(UUID tenantId) {
        assertSameTenant(tenantId);
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(FIND_ATTACHMENTS_REFERENCING_PENDING_SQL)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        Set<String> keys = new HashSet<>();
        for (Object[] row : rows) {
            UUID messageId = toUuid(row[0]);
            keys.addAll(EmailAttachmentKeys.extractS3Keys(messageId, (String) row[1]));
        }

        log.debug("[EmailMessageRepo] Referencje pending (BE-131): tenant={}, wiadomości={}, kluczy={}",
                tenantId, rows.size(), keys.size());
        return keys;
    }

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera wiadomość po samym {@code message_id} — BEZ klucza partycji (BE-134).
     *
     * <p>Używane tam, gdzie wołający zna wyłącznie identyfikator: endpointy REST z {@code {id}} w URL,
     * wysyłka odpowiedzi ({@code EmailSendService}), podgląd kontaktu z {@code channelMetadata} oraz
     * zdarzenia RabbitMQ sprzed BE-134 (bez {@code messageAt}).
     *
     * <p><strong>Koszt:</strong> PostgreSQL nie zna partycji z samego {@code message_id}, więc odpytuje
     * indeks PK ({@code pk_email_message}) KAŻDEJ partycji — {@code Append} po wszystkich partycjach
     * miesięcznych i {@code _default}. Koszt rośnie z liczbą partycji, nie z wielkością tabeli. Wszędzie,
     * gdzie znany jest {@code message_at}, używaj {@link #findById(UUID, Instant)} (partition pruning).
     *
     * @param messageId UUID wiadomości (kolumna message_id)
     * @return Optional z wiadomością lub empty
     */
    @Transactional(readOnly = true)
    public Optional<EmailMessage> findById(UUID messageId) {
        setTenantContextInDb();
        List<EmailMessage> results = em.createQuery(
                        "SELECT m FROM EmailMessage m WHERE m.id = :id", EmailMessage.class)
                .setParameter("id", messageId)
                .setMaxResults(1)
                .getResultList();
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Pobiera wiadomość po PEŁNYM kluczu {@code (message_id, message_at)} (BE-134) — jeden wiersz,
     * partition pruning do partycji zawierającej {@code messageAt}.
     *
     * @param messageId UUID wiadomości
     * @param messageAt klucz partycjonowania (ta sama wartość, która została zapisana)
     * @return Optional z wiadomością lub empty
     */
    @Transactional(readOnly = true)
    public Optional<EmailMessage> findById(UUID messageId, Instant messageAt) {
        setTenantContextInDb();
        return Optional.ofNullable(em.find(EmailMessage.class, new EmailMessageId(messageId, messageAt)));
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
