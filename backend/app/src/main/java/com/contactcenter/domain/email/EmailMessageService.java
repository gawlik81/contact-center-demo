package com.contactcenter.domain.email;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Serwis domenowy zapewniający odczyt wiadomości email ({@link EmailMessage}) dla
 * konsumentów cross-domain ({@code domain.contact}) oraz dla {@code api.email.EmailController}.
 *
 * <p>Stanowi fasadę nad {@link EmailMessageRepository} – wszystkie metody są
 * prostym przekazaniem do repozytorium, bez dodatkowej logiki biznesowej.
 */
public interface EmailMessageService {

    /**
     * Pobiera wiadomość po UUID (PK).
     *
     * @param messageId UUID wiadomości (kolumna message_id)
     * @return Optional z wiadomością lub empty
     */
    Optional<EmailMessage> findById(UUID messageId);

    /**
     * Pobiera historię wiadomości dla kontaktu (paginacja).
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @param pageable  parametry paginacji
     * @return strona wiadomości posortowanych po {@code received_at DESC}
     */
    Page<EmailMessage> findByContactId(UUID contactId, UUID tenantId, Pageable pageable);

    /**
     * Pobiera pierwszą wiadomość INBOUND powiązaną z kontaktem (root kontaktu email).
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @return Optional z wiadomością lub empty gdy brak
     */
    Optional<EmailMessage> findFirstInboundByContactId(UUID contactId, UUID tenantId);

    /**
     * Pobiera listę wiadomości w wątku emailowym (pogrupowane po In-Reply-To).
     *
     * @param originalMessageIdHeader nagłówek Message-ID wiadomości oryginalnej
     * @param tenantId                UUID tenanta
     * @param pageable                parametry paginacji
     * @return strona wiadomości wątku posortowanych chronologicznie
     */
    Page<EmailMessage> findByThreadRootMessageId(String originalMessageIdHeader, UUID tenantId, Pageable pageable);

    /**
     * Pobiera paginowaną listę wiadomości dla bieżącego tenanta (widok listy).
     *
     * @param pageable parametry paginacji
     * @return strona wiadomości posortowanych po {@code created_at DESC}
     */
    Page<EmailMessage> findAll(Pageable pageable);

    /**
     * Zeruje {@code contact_id} dla wiadomości wskazujących na usuwane kontakty (retencja EPIC-29,
     * BE-113 – kategoria CONTACT_INTERACTIONS). Nie usuwa wierszy {@code email_message}, tylko
     * odcina referencję (kolumna nullable od V028).
     *
     * @param tenantId   UUID tenanta
     * @param contactIds lista UUID usuniętych kontaktów – pusta lista jest no-opem
     * @return liczba zaktualizowanych wierszy
     * @deprecated EPIC-30 (D1 = A, BE-125): purge kontaktu usuwa wiadomości wraz z obiektami S3 —
     *             użyj {@link #purgeByContactIds}. Podpięcie w {@code RetentionPurgeServiceImpl}
     *             i usunięcie tej metody: BE-126 (przy D1 = C BE-130 przywraca odcinanie referencji).
     */
    @Deprecated
    int detachContactReferences(UUID tenantId, List<UUID> contactIds);

    /**
     * Usuwa wiadomości e-mail wskazanych kontaktów wraz z ich obiektami S3 (załączniki) — retencja
     * EPIC-30, BE-125, założenie D1 = A (usuwanie zamiast odcinania).
     *
     * <p><strong>Kolejność „S3 przed wierszem":</strong>
     * <ol>
     *   <li>odczyt wiadomości tenanta z {@code contact_id IN (...)} wraz z {@code attachments}
     *       (krótka transakcja tylko-do-odczytu),</li>
     *   <li>usunięcie obiektów S3 z {@code attachments[*].s3_key} (BEZ transakcji DB),
     *       z allow-listą prefiksu {@code email-attachments/{tenantId}/} — cudzy klucz jest
     *       pomijany ({@link PurgedMessages#s3Rejected()}), a wiersz usuwany mimo to,</li>
     *   <li>{@code DELETE} WYŁĄCZNIE wiadomości, których wszystkie obiekty usunięto (krótka
     *       transakcja).</li>
     * </ol>
     * Awaria S3 zostawia wiadomość w bazie (kolejny purge ponowi ją — usuwanie obiektu jest
     * idempotentne); jej kontakt trafia do {@link PurgedMessages#contactIdsBlocked()}.
     * Po kilku porażkach S3 z rzędu ({@code EmailMessageServiceImpl.S3_FAIL_FAST_THRESHOLD}) faza S3
     * jest przerywana (kolejne obiekty nie są próbowane), żeby niedostępny S3 nie blokował wątku
     * purge na czas {@code timeout × liczba obiektów}.
     *
     * <p><strong>Prekontrakt:</strong> {@code TenantContext} ustawiony na {@code tenantId}
     * (wołający zarządza kontekstem; metoda NIGDY go nie czyści) oraz wywołanie POZA transakcją —
     * inaczej połączenie z puli byłoby trzymane przez cały czas I/O do S3. Nie dodawaj
     * {@code @Transactional} do implementacji.
     *
     * @param tenantId   UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param contactIds kontakty, których wiadomości mają zostać usunięte — {@code null}/pusta
     *                   lista to no-op
     * @return wynik z licznikami i kontaktami zablokowanymi (nigdy {@code null})
     * @throws IllegalStateException gdy {@code TenantContext} nie jest ustawiony (przy niepustej liście)
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     *         (przy niepustej liście; wtedy żaden obiekt S3 nie jest ruszany)
     */
    PurgedMessages purgeByContactIds(UUID tenantId, List<UUID> contactIds);

    // =========================================================================
    // BE-127: Retencja – sweep wiadomości OSIEROCONYCH (contact_id IS NULL) wg wieku (EPIC-30)
    // =========================================================================

    /**
     * Liczy wiadomości e-mail OSIEROCONE ({@code contact_id IS NULL}) starsze niż {@code cutoff} —
     * dry-run (WP-4) i dashboard/badge (BE-128). Kryterium DOKŁADNIE jak
     * {@link #purgeOrphansOlderThan}: wiek = {@code COALESCE(received_at, sent_at, created_at)}
     * (definicja z DB-059/BE-124 §7) ORAZ filtr resztkowy {@code created_at < now() − 1 dzień}
     * (świeże, jeszcze nieprzypisane wiadomości w toku routingu nigdy się nie liczą).
     *
     * @param tenantId UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param cutoff   granica czasowa retencji CONTACT_INTERACTIONS
     * @return liczba kwalifikujących się wiadomości
     * @throws IllegalStateException gdy {@code TenantContext} nie jest ustawiony
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    long countOrphansOlderThan(UUID tenantId, Instant cutoff);

    /**
     * Usuwa JEDNĄ STRONĘ wiadomości e-mail OSIEROCONYCH ({@code contact_id IS NULL}) starszych niż
     * {@code cutoff}, wraz z ich obiektami S3 — retencja EPIC-30, BE-127 (założenie D1 = A, ta sama
     * flaga bezpiecznika co BE-126: {@code retention.purge.delete-messages}).
     *
     * <p>Kryterium: {@code tenant_id = :t AND contact_id IS NULL AND <wiek> < cutoff AND created_at
     * < now() − 1 dzień} — DOKŁADNIE wyrażenie „wieku" z DB-059
     * ({@code COALESCE(received_at, sent_at, created_at)}), dopasowane do predykatu częściowego
     * indeksu {@code idx_email_message_tenant_orphan_age}. Faza S3+DELETE jest DZIELONA z
     * {@link #purgeByContactIds} ({@code EmailMessageServiceImpl#purgeRows}) — allow-lista prefiksu,
     * kolejność „S3 przed wierszem" i potwierdzenie {@code DELETE … RETURNING} są identyczne.
     *
     * <p><strong>Stronicowanie keyset (strategia H-1, jak BE-126):</strong> wywołujący
     * ({@code RetentionPurgeServiceImpl}) trzyma {@link EmailOrphanCursor} między wywołaniami i
     * kontynuuje pętlę, gdy {@link OrphanEmailPurgeBatch#candidatesFound()} == {@code batchSize} —
     * kursor przesuwa się o CAŁĄ stronę niezależnie od tego, ile wiadomości faktycznie usunięto
     * (porażka S3 nie zatrzymuje sweepu młodszych sierot).
     *
     * <p><strong>Prekontrakt:</strong> {@code TenantContext} ustawiony na {@code tenantId}
     * (wołający zarządza kontekstem; metoda NIGDY go nie czyści) oraz wywołanie POZA transakcją.
     *
     * @param tenantId  UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param cursor    kursor poprzedniej strony ({@code null} dla pierwszej strony)
     * @param cutoff    granica czasowa retencji CONTACT_INTERACTIONS
     * @param batchSize maksymalna liczba kandydatów na stronę
     * @return wynik strony: liczniki S3/DELETE, liczba kandydatów na stronie i kursor kolejnej strony
     * @throws IllegalStateException gdy {@code TenantContext} nie jest ustawiony
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    OrphanEmailPurgeBatch purgeOrphansOlderThan(UUID tenantId, EmailOrphanCursor cursor, Instant cutoff, int batchSize);
}
