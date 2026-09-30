package com.contactcenter.domain.email;

import com.contactcenter.domain.email.EmailMessageRepository.AttachmentsRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class EmailMessageServiceImpl implements EmailMessageService {

    /**
     * Po tylu porażkach usunięcia obiektu S3 Z RZĘDU faza S3 w {@link #purgeByContactIds} jest
     * przerywana. Domyślny {@code S3Client} ponawia żądania i ma timeouty rzędu sekund, więc przy
     * niedostępnym S3 partia setek obiektów blokowałaby wątek purge na dziesiątki minut. Wiadomości
     * z niepróbowanymi obiektami zostają, ich kontakty trafiają do {@code contactIdsBlocked}.
     */
    static final int S3_FAIL_FAST_THRESHOLD = 3;

    private final EmailMessageRepository emailMessageRepository;
    private final EmailAttachmentStorageService attachmentStorageService;

    @Override
    @Transactional(readOnly = true)
    public Optional<EmailMessage> findById(UUID messageId) {
        return emailMessageRepository.findById(messageId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EmailMessage> findByContactId(UUID contactId, UUID tenantId, Pageable pageable) {
        return emailMessageRepository.findByContactId(contactId, tenantId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EmailMessage> findFirstInboundByContactId(UUID contactId, UUID tenantId) {
        return emailMessageRepository.findFirstInboundByContactId(contactId, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EmailMessage> findByThreadRootMessageId(
            String originalMessageIdHeader, UUID tenantId, Pageable pageable) {
        return emailMessageRepository.findByThreadRootMessageId(originalMessageIdHeader, tenantId, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EmailMessage> findAll(Pageable pageable) {
        return emailMessageRepository.findAll(pageable);
    }

    @Override
    @Transactional
    @Deprecated // EPIC-30 (BE-125): zastąpione przez purgeByContactIds; usunięcie w BE-126
    public int detachContactReferences(UUID tenantId, List<UUID> contactIds) {
        return emailMessageRepository.detachContactReferences(tenantId, contactIds);
    }

    // =========================================================================
    // BE-125: Retencja – usuwanie wiadomości wraz z obiektami S3 (EPIC-30)
    // =========================================================================

    /**
     * Celowo BEZ {@code @Transactional}: środek metody to I/O do S3 (potencjalnie setki wywołań),
     * a transakcja trzymałaby przez ten czas połączenie z puli HikariCP (zasada projektu: brak
     * I/O do zewnętrznych usług wewnątrz transakcji). Każda z dwóch operacji DB
     * ({@code findAttachmentsByContactIds}, {@code deleteByIds}) ma własną, krótką transakcję
     * repozytorium. Poprawność nie wymaga atomowości całości: usunięcie obiektu jest idempotentne,
     * a wiersz znika dopiero PO skutecznym usunięciu obiektów.
     */
    @Override
    public PurgedMessages purgeByContactIds(UUID tenantId, List<UUID> contactIds) {
        if (contactIds == null || contactIds.isEmpty()) {
            return PurgedMessages.empty();
        }

        List<AttachmentsRow> rows = emailMessageRepository.findAttachmentsByContactIds(tenantId, contactIds);
        return purgeRows(tenantId, rows);
    }

    /**
     * Fazy 2 i 3 „S3 przed wierszem" dla już wybranych wiadomości — niezależne od tego, JAK wiadomości
     * wybrano (po kontaktach: {@link #purgeByContactIds}; osierocone wg wieku: BE-127 użyje tej samej
     * logiki, więc S3, allow-lista i potwierdzenie {@code DELETE} są w jednym miejscu).
     *
     * <p>{@code AttachmentsRow#contactId()} może być {@code null} (wiadomość osierocona) — taka wiadomość
     * nie wpływa na {@code contactIdsBlocked}.
     */
    PurgedMessages purgeRows(UUID tenantId, List<AttachmentsRow> rows) {
        if (rows.isEmpty()) {
            return PurgedMessages.empty();
        }

        // Faza 2: S3 przed wierszem. Wiersz kwalifikuje się do usunięcia tylko wtedy, gdy KAŻDY jego
        // klucz został usunięty (albo pominięty przez allow-listę — cudzego obiektu nie ruszamy,
        // ale PII wiadomości musi zniknąć).
        S3Phase s3 = new S3Phase(tenantId);
        Map<UUID, AttachmentsRow> deletable = new LinkedHashMap<>();
        Set<UUID> blockedContacts = new HashSet<>();
        for (AttachmentsRow row : rows) {
            boolean allObjectsGone = true;
            for (String key : EmailAttachmentKeys.extractS3Keys(row.messageId(), row.attachmentsJson())) {
                if (!s3.delete(key, row.messageId())) {
                    allObjectsGone = false; // kolejne klucze tej wiadomości też próbujemy — każdy usunięty to postęp
                }
            }
            if (allObjectsGone) {
                deletable.put(row.messageId(), row);
            } else if (row.contactId() != null) {
                blockedContacts.add(row.contactId());
            }
        }

        // Faza 3: DELETE tylko wiadomości z usuniętymi obiektami; potwierdzenie przez RETURNING.
        Set<UUID> deletedIds = deletable.isEmpty()
                ? Set.of()
                : emailMessageRepository.deleteByIds(tenantId, deletable.keySet());

        // Wiersz zlecony do usunięcia, a niepotwierdzony (RLS bez polityki DELETE pod rolą bez
        // BYPASSRLS usuwa 0 wierszy bez błędu — DESIGN U8/R1; albo wyścig z równoległym purge):
        // kontakt zostaje zablokowany, żeby wołający go nie usunął zostawiając wiadomość z PII.
        int unconfirmed = 0;
        for (AttachmentsRow row : deletable.values()) {
            if (!deletedIds.contains(row.messageId())) {
                if (row.contactId() != null) {
                    blockedContacts.add(row.contactId());
                }
                unconfirmed++;
            }
        }
        if (unconfirmed > 0) {
            log.warn("[EmailMessage][Purge] {} wiadomość(i) nie potwierdzono jako usunięte po usunięciu obiektów S3 "
                            + "(RLS/wyścig?): tenant={} — kontakty zablokowane",
                    unconfirmed, tenantId);
        }

        PurgedMessages result = new PurgedMessages(
                deletedIds.size(), s3.deleted(), s3.failures(), s3.rejected(), blockedContacts);
        log.info("[EmailMessage][Purge] tenant={}, wiadomości={}, usunięto={}, s3Usunięto={}, s3Błędy={}, "
                        + "s3Odrzucone={}, kontaktyZablokowane={}",
                tenantId, rows.size(), result.deletedRows(), result.s3ObjectsDeleted(),
                result.s3Failures(), result.s3Rejected(), result.contactIdsBlocked().size());
        return result;
    }

    // =========================================================================
    // BE-127: Retencja – sweep wiadomości OSIEROCONYCH (contact_id IS NULL) wg wieku (EPIC-30)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public long countOrphansOlderThan(UUID tenantId, Instant cutoff) {
        return emailMessageRepository.countOrphansOlderThan(tenantId, cutoff);
    }

    // =========================================================================
    // BE-128: Retencja – liczenie wiadomości POWIĄZANYCH z kontaktami kwalifikującymi się (EPIC-30)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public long countLinkedToContactsOlderThan(UUID tenantId, Instant cutoff) {
        return emailMessageRepository.countLinkedToContactsOlderThan(tenantId, cutoff);
    }

    /**
     * Celowo BEZ {@code @Transactional} — jak {@link #purgeByContactIds}: {@link #purgeRows} robi
     * I/O do S3. {@link EmailMessageRepository#findOrphansOlderThan} ma własną, krótką transakcję
     * tylko-do-odczytu.
     */
    @Override
    public OrphanEmailPurgeBatch purgeOrphansOlderThan(
            UUID tenantId, EmailOrphanCursor cursor, Instant cutoff, int batchSize) {
        List<EmailMessageRepository.OrphanCandidate> page =
                emailMessageRepository.findOrphansOlderThan(tenantId, cutoff, cursor, batchSize);
        if (page.isEmpty()) {
            return new OrphanEmailPurgeBatch(PurgedMessages.empty(), 0, cursor);
        }

        List<AttachmentsRow> rows = page.stream()
                .map(EmailMessageRepository.OrphanCandidate::toAttachmentsRow)
                .toList();
        PurgedMessages purged = purgeRows(tenantId, rows);

        EmailMessageRepository.OrphanCandidate last = page.get(page.size() - 1);
        EmailOrphanCursor nextCursor = new EmailOrphanCursor(last.messageAt(), last.messageId());
        return new OrphanEmailPurgeBatch(purged, page.size(), nextCursor);
    }

    /**
     * Stan fazy S3 jednego wywołania purge: allow-lista prefiksu tenanta, pamięć wyników per klucz
     * (ten sam klucz w kilku wiadomościach jest usuwany raz i ma jeden wynik dla wszystkich),
     * liczniki oraz bezpiecznik po {@link #S3_FAIL_FAST_THRESHOLD} porażkach z rzędu.
     */
    private final class S3Phase {

        private final UUID tenantId;
        private final Map<String, Boolean> results = new HashMap<>();
        private final Set<String> rejectedKeys = new HashSet<>();
        private int deleted;
        private int failures;
        private int consecutiveFailures;
        private boolean aborted;

        private S3Phase(UUID tenantId) {
            this.tenantId = tenantId;
        }

        /**
         * @return {@code true} gdy wiadomość NIE jest blokowana przez ten klucz (obiekt usunięty albo
         *         klucz odrzucony przez allow-listę); {@code false} gdy obiekt nie został usunięty
         */
        boolean delete(String key, UUID messageId) {
            if (!EmailAttachmentKeys.isOwnedByTenant(tenantId, key)) {
                // Klucz OUTBOUND to dane od klienta — nie kasujemy cudzego obiektu (inny tenant, nagranie, EML).
                if (rejectedKeys.add(key)) {
                    log.error("[EmailMessage][Purge] Klucz S3 spoza prefiksu {}: pomijam obiekt, wiadomość zostanie "
                                    + "usunięta: tenant={}, messageId={}, s3Key={}",
                            EmailAttachmentKeys.tenantPrefix(tenantId), tenantId, messageId,
                            EmailAttachmentKeys.forLog(key));
                }
                return true;
            }

            Boolean known = results.get(key);
            if (known != null) {
                return known;
            }
            if (aborted) {
                return false; // nie próbowany po przerwaniu fazy S3 — wiadomość zostaje
            }

            try {
                attachmentStorageService.delete(key);
                results.put(key, true);
                deleted++;
                consecutiveFailures = 0;
                return true;
            } catch (RuntimeException e) {
                // Każdy błąd = obiekt mógł nie zostać usunięty → wiersz zostaje (ponowi go następny purge).
                results.put(key, false);
                failures++;
                consecutiveFailures++;
                // Stack trace loguje już storage (EmailAttachmentStorageServiceImpl#delete) — tu bez duplikatu.
                log.error("[EmailMessage][Purge] Nie udało się usunąć obiektu S3 — wiadomość zostaje: tenant={}, "
                                + "messageId={}, s3Key={}, error={}",
                        tenantId, messageId, EmailAttachmentKeys.forLog(key), e.getMessage());
                if (consecutiveFailures >= S3_FAIL_FAST_THRESHOLD) {
                    aborted = true;
                    log.error("[EmailMessage][Purge] {} porażki S3 z rzędu — przerywam fazę S3: tenant={} "
                                    + "(niepróbowane obiekty i ich wiadomości zostają do kolejnego purge)",
                            consecutiveFailures, tenantId);
                }
                return false;
            }
        }

        int deleted() {
            return deleted;
        }

        int failures() {
            return failures;
        }

        int rejected() {
            return rejectedKeys.size();
        }
    }
}
