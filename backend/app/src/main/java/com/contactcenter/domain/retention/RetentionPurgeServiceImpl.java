package com.contactcenter.domain.retention;

import com.contactcenter.domain.audit.AuditLogEvent;
import com.contactcenter.domain.audit.AuditLogService;
import com.contactcenter.domain.contact.ContactEventService;
import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.email.EmailMessageService;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.retention.dto.PurgeResultDto;
import com.contactcenter.domain.retention.dto.RetentionSummaryDto;
import com.contactcenter.domain.social.SocialMessageService;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementacja {@link RetentionPurgeService} — silnik usuwania per-tenant dla kategorii
 * {@code CONTACT_INTERACTIONS} i {@code TRANSCRIPTS} (batchowane, EPIC-29, BE-113) oraz
 * {@code CAMPAIGN_DATA} (pojedynczy DELETE przez funkcję SQL, BE-119).
 *
 * <p><strong>Wzorzec asynchroniczny + self-invocation:</strong> {@link #purge} zapisuje stan
 * {@code RUNNING} do {@code retention_purge_log} (transakcja własna repozytorium — {@link #purge}
 * sama NIE jest {@code @Transactional}, żeby ten zapis COMMITOWAŁ się natychmiast, zanim
 * {@link #purgeAsync} wystartuje w osobnym wątku i mogłaby wyścigowo zobaczyć jeszcze
 * niezacommitowany wiersz) i zwraca {@code purgeId} wywołującemu. Faktyczne usuwanie odbywa się
 * w {@link #purgeAsync}, wywoływanej przez self-injected proxy ({@link #self}) zamiast {@code this.}
 * — self-invocation przez {@code this.purgeAsync(...)} pominęłoby Spring AOP proxy i adnotacja
 * {@code @Async} nigdy by nie zadziałała (sprawdzony, działający wzorzec z tego repo:
 * {@code ProgressiveDialerServiceImpl.self}, wymaga zadeklarowania metody w interfejsie, żeby
 * wywołanie przez wstrzyknięty do siebie samego bean przeszło przez proxy).
 *
 * <p><strong>Wzorzec batchowania — dlaczego nie {@code ctid}:</strong> zobacz uzasadnienie w
 * {@code ContactRepository#deleteBatchOlderThan} — na tabeli partycjonowanej fizyczny
 * {@code ctid} nie jest unikalny globalnie (kolizje między partycjami), więc wszystkie zapytania
 * DELETE identyfikują wiersze przez pełny klucz główny (kolumna techniczna + kolumna
 * partycjonowania), nigdy przez {@code ctid}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class RetentionPurgeServiceImpl implements RetentionPurgeService {

    /** Domyślny rozmiar batcha gdy {@code retention.purge.batch-size} nie jest skonfigurowany. */
    static final int DEFAULT_BATCH_SIZE = 100;

    private static final String AUDIT_ENTITY_TYPE = "RETENTION_PURGE";
    private static final String AUDIT_ACTION_COMPLETED = "RETENTION_PURGE_COMPLETED";
    private static final String AUDIT_ACTION_FAILED = "RETENTION_PURGE_FAILED";

    private final RetentionPolicyService retentionPolicyService;
    private final RetentionPurgeLogRepository purgeLogRepository;
    private final ContactService contactService;
    private final ContactEventService contactEventService;
    private final EmailMessageService emailMessageService;
    private final SocialMessageService socialMessageService;
    private final AuditLogService auditLogService;
    private final TenantRetentionPendingSummaryRepository summaryRepository;
    private final CampaignArchiveRetentionRepository campaignArchiveRetentionRepository;

    @Value("${retention.purge.batch-size:100}")
    private int batchSize;

    /**
     * Self-reference przez {@code @Lazy} – pozwala wywoływać {@code @Async} metodę przez proxy
     * Springa. Bez tego self-invocation ({@code this.purgeAsync(...)}) omijałoby proxy i metoda
     * wykonywałaby się synchronicznie w wątku wywołującego {@link #purge}, blokując go aż do
     * zakończenia całego usuwania.
     */
    @Autowired
    @Lazy
    private RetentionPurgeService self;

    // =========================================================================
    // Inicjowanie purge (synchroniczne – szybkie)
    // =========================================================================

    @Override
    public UUID purge(UUID tenantId, RetentionDataCategory category,
                       PurgeTriggerType triggerType, UUID triggeredByUserId) {
        validateSupportedCategory(category);

        int retentionMonths = retentionPolicyService.getRetentionMonths(tenantId, category);
        LocalDate cutoffDate = LocalDate.now(ZoneOffset.UTC).minusMonths(retentionMonths);

        UUID purgeId = UUID.randomUUID();

        // Zapis RUNNING PRZED zwróceniem purgeId — kryterium akceptacji BE-113. Metoda insertRunning
        // ma własną @Transactional (REQUIRED, brak otwartej transakcji na tym poziomie), więc commituje
        // natychmiast i jest widoczna w bazie zanim self.purgeAsync wystartuje w osobnym wątku.
        purgeLogRepository.insertRunning(purgeId, tenantId, category, triggerType, triggeredByUserId, cutoffDate);

        log.info("[RetentionPurge] Rozpoczęto purge: purgeId={}, tenant={}, category={}, trigger={}, "
                        + "retentionMonths={}, cutoff={}",
                purgeId, tenantId, category, triggerType, retentionMonths, cutoffDate);

        TenantContext.Snapshot snapshot = TenantContext.snapshot();
        self.purgeAsync(purgeId, tenantId, category, cutoffDate, triggeredByUserId, snapshot);

        return purgeId;
    }

    // =========================================================================
    // Wykonanie asynchroniczne
    // =========================================================================

    @Override
    @Async("applicationTaskExecutor")
    public void purgeAsync(UUID purgeId, UUID tenantId, RetentionDataCategory category,
                            LocalDate cutoffDate, UUID triggeredByUserId, TenantContext.Snapshot snapshot) {
        TenantContext.restore(snapshot);
        try {
            Instant cutoff = cutoffDate.atStartOfDay(ZoneOffset.UTC).toInstant();

            long rowsDeleted = switch (category) {
                case CONTACT_INTERACTIONS -> purgeContactInteractions(tenantId, cutoff);
                case TRANSCRIPTS -> purgeTranscripts(tenantId, cutoff);
                case CAMPAIGN_DATA -> purgeCampaignData(tenantId, cutoff);
                // Nieosiągalne w praktyce – validateSupportedCategory już odrzuciła tę wartość
                // w purge(), zanim purgeAsync w ogóle wystartował. Zabezpieczenie defensywne.
                case RECORDINGS -> throw new UnsupportedOperationException(
                        "Kategoria " + category + " nie jest obsługiwana przez RetentionPurgeService");
            };

            purgeLogRepository.markCompleted(purgeId, tenantId, rowsDeleted);
            publishAudit(tenantId, purgeId, triggeredByUserId, AUDIT_ACTION_COMPLETED,
                    buildNewValueJson(rowsDeleted, RetentionPurgeLog.STATUS_COMPLETED, null));

            log.info("[RetentionPurge] Zakończono purge: purgeId={}, tenant={}, category={}, rowsDeleted={}",
                    purgeId, tenantId, category, rowsDeleted);

        } catch (Exception e) {
            log.error("[RetentionPurge] Błąd purge: purgeId={}, tenant={}, category={}, error={}",
                    purgeId, tenantId, category, e.getMessage(), e);
            handleFailure(purgeId, tenantId, triggeredByUserId, e);
        } finally {
            TenantContext.clear();
        }
    }

    // =========================================================================
    // Usuwanie per kategoria
    // =========================================================================

    /**
     * Usuwa dane kategorii CONTACT_INTERACTIONS: {@code contact} (+ odcięcie referencji
     * {@code email_message}/{@code social_message} dla usuniętych kontaktów) oraz {@code contact_event}.
     *
     * <p>Batche {@code contact} są przetwarzane najpierw, w całości, po czym następują batche
     * {@code contact_event} — kolejność nie ma znaczenia biznesowego (obie tabele identyfikują
     * wiersze do usunięcia niezależnie po własnym {@code tenant_id}/{@code started_at}), ale
     * ułatwia odcinanie FK email/social per-batch (od razu po każdym batchu {@code contact},
     * zamiast trzymać pełną listę usuniętych ID w pamięci do końca operacji).
     */
    private long purgeContactInteractions(UUID tenantId, Instant cutoff) {
        long totalDeleted = 0;
        int effectiveBatchSize = effectiveBatchSize();

        List<UUID> deletedContactIds;
        do {
            deletedContactIds = contactService.purgeContactsOlderThan(tenantId, cutoff, effectiveBatchSize);
            if (!deletedContactIds.isEmpty()) {
                emailMessageService.detachContactReferences(tenantId, deletedContactIds);
                socialMessageService.detachContactReferences(tenantId, deletedContactIds);
                totalDeleted += deletedContactIds.size();
            }
        } while (deletedContactIds.size() == effectiveBatchSize);

        int eventsDeletedInBatch;
        do {
            eventsDeletedInBatch = contactEventService.purgeOlderThan(tenantId, cutoff, effectiveBatchSize);
            totalDeleted += eventsDeletedInBatch;
        } while (eventsDeletedInBatch == effectiveBatchSize);

        return totalDeleted;
    }

    /**
     * Usuwa dane kategorii TRANSCRIPTS: {@code contact_transcription} oraz {@code contact_ai_summary}.
     */
    private long purgeTranscripts(UUID tenantId, Instant cutoff) {
        long totalDeleted = 0;
        int effectiveBatchSize = effectiveBatchSize();

        int transcriptionsDeletedInBatch;
        do {
            transcriptionsDeletedInBatch = contactService.purgeTranscriptionsOlderThan(tenantId, cutoff, effectiveBatchSize);
            totalDeleted += transcriptionsDeletedInBatch;
        } while (transcriptionsDeletedInBatch == effectiveBatchSize);

        int summariesDeletedInBatch;
        do {
            summariesDeletedInBatch = contactService.purgeAiSummariesOlderThan(tenantId, cutoff, effectiveBatchSize);
            totalDeleted += summariesDeletedInBatch;
        } while (summariesDeletedInBatch == effectiveBatchSize);

        return totalDeleted;
    }

    /**
     * Usuwa dane kategorii CAMPAIGN_DATA: {@code campaign_contact_archive} (BE-119).
     *
     * <p><strong>Dlaczego delegacja do funkcji SQL zamiast batchowania po stronie Javy</strong>
     * (jak {@link #purgeContactInteractions} / {@link #purgeTranscripts}): {@code campaign_contact_archive}
     * NIE jest partycjonowana i NIE ma włączonego RLS (w odróżnieniu od większości tabel domenowych)
     * — patrz javadoc {@link CampaignArchiveRetentionRepository}. Indeks
     * {@code idx_cca_tenant_archived_at} (V089) czyni jeden natywny DELETE efektywnym nawet dla
     * dużych wolumenów, więc batchowanie po stronie Javy nie daje tu dodatkowej korzyści, jedynie
     * zwiększa liczbę round-tripów do bazy.
     *
     * <p>Delegacja do {@code purge_campaign_contact_archive(p_tenant_id, p_cutoff_date)} (V091) —
     * funkcja filtruje po {@code tenant_id} PRZED DELETE, co jest jedynym mechanizmem izolacji
     * tenantów dla tej tabeli (brak RLS jako drugiej warstwy ochrony, w przeciwieństwie do
     * większości pozostałych operacji purge w tym serwisie).
     */
    private long purgeCampaignData(UUID tenantId, Instant cutoff) {
        return campaignArchiveRetentionRepository.purgeEligible(tenantId, cutoff);
    }

    // =========================================================================
    // Odczyt (BE-118 — RetentionController)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public PurgeResultDto getPurgeStatus(UUID tenantId, UUID purgeId) {
        return purgeLogRepository.findById(purgeId, tenantId)
                .map(PurgeResultDto::from)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Operacja purge nie istnieje: purgeId=" + purgeId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PurgeResultDto> getPurgeHistory(UUID tenantId, Pageable pageable) {
        return purgeLogRepository.findAllByTenantId(tenantId, pageable).map(PurgeResultDto::from);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RetentionSummaryDto> getPendingSummary(UUID tenantId) {
        Map<RetentionDataCategory, TenantRetentionPendingSummaryRepository.PendingSummaryRow> byCategory =
                summaryRepository.findAllByTenantId(tenantId).stream()
                        .collect(Collectors.toMap(
                                TenantRetentionPendingSummaryRepository.PendingSummaryRow::dataCategory,
                                row -> row));

        // ZAWSZE 4 wpisy (jeden per RetentionDataCategory), nie tylko te obecne w cache —
        // kryterium akceptacji BE-118, patrz Javadoc RetentionSummaryDto.
        return Arrays.stream(RetentionDataCategory.values())
                .map(category -> toSummaryDto(category, byCategory.get(category)))
                .toList();
    }

    private RetentionSummaryDto toSummaryDto(RetentionDataCategory category,
            TenantRetentionPendingSummaryRepository.PendingSummaryRow row) {
        if (row == null) {
            // Brak wiersza w cache = "jeszcze nie policzone przez RetentionEvaluationJob",
            // NIE "zero do usunięcia" — computed=false odróżnia te dwa stany.
            return new RetentionSummaryDto(category, 0L, null, null, null, false);
        }
        return new RetentionSummaryDto(category, row.eligibleRowCount(), row.oldestEligiblePeriod(),
                row.newestEligiblePeriod(), row.computedAt(), true);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Zwraca skonfigurowany {@link #batchSize}, z ochroną przed nieprawidłową konfiguracją
     * (0 lub ujemny spowodowałby nieskończoną pętlę {@code while (batch.size() == batchSize)}).
     */
    private int effectiveBatchSize() {
        return batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE;
    }

    private void validateSupportedCategory(RetentionDataCategory category) {
        if (category != RetentionDataCategory.CONTACT_INTERACTIONS
                && category != RetentionDataCategory.TRANSCRIPTS
                && category != RetentionDataCategory.CAMPAIGN_DATA) {
            throw new UnsupportedOperationException(
                    "RetentionPurgeService (BE-113/BE-119) obsługuje CONTACT_INTERACTIONS, TRANSCRIPTS "
                            + "i CAMPAIGN_DATA. Kategoria " + category + " nie jest obsługiwana: "
                            + "RECORDINGS → RecordingRetentionJob (BE-116).");
        }
    }

    private void handleFailure(UUID purgeId, UUID tenantId, UUID triggeredByUserId, Exception e) {
        try {
            // rowsDeleted=0 – batche już zacommitowane przed wyjątkiem pozostają usunięte w DB
            // (każdy batch to osobna transakcja repozytorium), ale nie mamy tu łącznej sumy
            // częściowego postępu bez dodatkowego zapytania COUNT; 0 sygnalizuje "nieznana/częściowa"
            // liczba, odróżnialna od sukcesu przez status=FAILED.
            purgeLogRepository.markFailed(purgeId, tenantId, e.getMessage(), 0);
            publishAudit(tenantId, purgeId, triggeredByUserId, AUDIT_ACTION_FAILED,
                    buildNewValueJson(0, RetentionPurgeLog.STATUS_FAILED, e.getMessage()));
        } catch (Exception logEx) {
            log.error("[RetentionPurge] Nie udało się zapisać błędu do retention_purge_log: purgeId={}",
                    purgeId, logEx);
        }
    }

    private void publishAudit(UUID tenantId, UUID purgeId, UUID triggeredByUserId, String action, String newValueJson) {
        try {
            auditLogService.publishAuditEvent(new AuditLogEvent(
                    tenantId,
                    triggeredByUserId,
                    action,
                    AUDIT_ENTITY_TYPE,
                    purgeId,
                    null,
                    newValueJson,
                    null,
                    null,
                    Instant.now()
            ));
        } catch (Exception auditEx) {
            log.warn("[RetentionPurge] Nie udało się opublikować zdarzenia audytowego: purgeId={}, error={}",
                    purgeId, auditEx.getMessage());
        }
    }

    /**
     * Buduje JSON dla pola {@code newValue} wpisu audytowego.
     *
     * @param rowsDeleted  suma usuniętych wierszy (0 przy błędzie/przed pierwszym batchem)
     * @param status       {@code COMPLETED} lub {@code FAILED}
     * @param errorMessage komunikat błędu (null przy sukcesie) – escapowany dla poprawności JSON
     */
    private String buildNewValueJson(long rowsDeleted, String status, String errorMessage) {
        if (errorMessage == null) {
            return String.format("{\"status\":\"%s\",\"rowsDeleted\":%d}", status, rowsDeleted);
        }
        String escaped = errorMessage.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
        return String.format("{\"status\":\"%s\",\"errorMessage\":\"%s\"}", status, escaped);
    }
}
