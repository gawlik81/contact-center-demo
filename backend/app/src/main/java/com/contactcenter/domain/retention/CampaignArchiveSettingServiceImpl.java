package com.contactcenter.domain.retention;

import com.contactcenter.domain.audit.AuditLogEvent;
import com.contactcenter.domain.audit.AuditLogService;
import com.contactcenter.domain.retention.dto.CampaignArchivePreviewDto;
import com.contactcenter.domain.retention.dto.CampaignArchiveSettingDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementacja {@link CampaignArchiveSettingService} (BE-151).
 *
 * <h2>Audyt — decyzje</h2>
 * <ul>
 *   <li>Wpis {@code CAMPAIGN_ARCHIVE_PERIOD_CHANGED} (entityType {@code TENANT_CAMPAIGN_ARCHIVE_SETTING},
 *       entityId = tenantId = PK ustawienia) powstaje WYŁĄCZNIE gdy zmienia się wartość EFEKTYWNA
 *       (stara efektywna != nowa). {@code PUT} z tą samą wartością co obecna NIE zapisuje nic i NIE
 *       audytuje (idempotentny no-op — brak spamu przy ponawianiu żądań/autozapisie GUI).</li>
 *   <li>Pierwszy jawny zapis wartości równej domyślnej (np. 180 bez wiersza) tworzy wiersz
 *       ("przypięcie" wartości), ale nie zmienia efektywnego okresu, więc nie jest audytowane
 *       (tylko log INFO).</li>
 *   <li>{@code oldValue}/{@code newValue}: JSON z {@code archiveAfterDays}; {@code newValue} ma
 *       dodatkowo flagę {@code shortened} (nowy okres < stary: skutek = masowa archiwizacja przy
 *       najbliższym przebiegu). Kto: {@code userId} zdarzenia = zmieniający admin.</li>
 *   <li>Atomowość: stara wartość jest odczytywana W TEJ SAMEJ transakcji co zapis
 *       ({@link TenantCampaignArchiveSettingRepository#upsertReturningPrevious}: INSERT ... ON CONFLICT
 *       DO NOTHING, potem SELECT ... FOR UPDATE i UPDATE). Równoległe PUT serializują się na wierszu,
 *       więc audyt i flaga {@code shortened} zawsze opisują faktyczne przejście "wartość tuż przed
 *       naszym zapisem" -> "nowa". Wybrano to zamiast {@code @Transactional} na {@link #update}
 *       (publikacja audytu wymagałaby {@code afterCommit}) i zamiast CTE (snapshot instrukcji nie
 *       widzi zapisu konkurenta zacommitowanego w trakcie oczekiwania na blokadę). Transakcja
 *       repozytorium jest zacommitowana, zanim publikujemy zdarzenie — audyt nie opisze wycofanej
 *       zmiany.</li>
 *   <li><b>Audyt jest BEST-EFFORT, nie gwarantowany.</b> {@code AuditLogService#publishAuditEvent} jest
 *       {@code @Async} i sam połyka błędy brokera ({@code AmqpException} — logowane tylko jako ERROR),
 *       więc {@code try/catch} poniżej NIE złapie awarii RabbitMQ; łapie wyłącznie błędy synchroniczne
 *       (np. {@code TaskRejectedException} przy przepełnionym executorze). Zmiana okresu (w tym
 *       skracająca = masowa archiwizacja) może więc zostać zapisana bez wpisu w {@code audit_log}.
 *       Spójne z {@code RetentionPurgeServiceImpl}; utrata śladu jest logowana na poziomie ERROR.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
class CampaignArchiveSettingServiceImpl implements CampaignArchiveSettingService {

    static final String AUDIT_ACTION = "CAMPAIGN_ARCHIVE_PERIOD_CHANGED";
    static final String AUDIT_ENTITY_TYPE = "TENANT_CAMPAIGN_ARCHIVE_SETTING";

    private final TenantCampaignArchiveSettingRepository repository;
    private final AuditLogService auditLogService;

    @Override
    @Transactional(readOnly = true)
    public CampaignArchiveSettingDto get(UUID tenantId) {
        return toDto(repository.findByTenantId(tenantId));
    }

    @Override
    public CampaignArchiveSettingDto update(UUID tenantId, Integer archiveAfterDays, UUID updatedByUserId) {
        int newDays = validate(archiveAfterDays);

        TenantCampaignArchiveSettingRepository.UpsertOutcome outcome =
                repository.upsertReturningPrevious(tenantId, newDays, updatedByUserId);
        boolean hadExplicitValue = outcome.previousDays() != null;
        int oldEffective = hadExplicitValue ? outcome.previousDays() : DEFAULT_ARCHIVE_AFTER_DAYS;

        if (!outcome.written()) {
            log.debug("[CampaignArchiveSetting] PUT bez zmiany wartości: tenant={}, days={} — pomijam zapis i audyt",
                    tenantId, newDays);
            return toDto(outcome.current());
        }

        boolean effectiveChanged = oldEffective != newDays;
        boolean shortened = newDays < oldEffective;
        if (effectiveChanged) {
            log.info("[CampaignArchiveSetting] Zmiana okresu archiwizacji: tenant={}, {} -> {} dni, user={}{}",
                    tenantId, oldEffective, newDays, updatedByUserId,
                    shortened ? " (SKRÓCENIE: masowa archiwizacja przy najbliższym przebiegu)" : "");
            publishAudit(tenantId, updatedByUserId, hadExplicitValue, oldEffective, newDays, shortened);
        } else {
            log.info("[CampaignArchiveSetting] Przypięto jawnie wartość domyślną: tenant={}, days={}",
                    tenantId, newDays);
        }
        return toDto(outcome.current());
    }

    @Override
    @Transactional(readOnly = true)
    public CampaignArchivePreviewDto preview(UUID tenantId, Integer newDaysParam) {
        int newDays = validate(newDaysParam);
        int currentDays = repository.findByTenantId(tenantId)
                .map(TenantCampaignArchiveSetting::getArchiveAfterDays)
                .orElse(DEFAULT_ARCHIVE_AFTER_DAYS);

        TenantCampaignArchiveSettingRepository.ArchivableCounts atNew = repository.countArchivable(tenantId, newDays);
        TenantCampaignArchiveSettingRepository.ArchivableCounts atCurrent =
                newDays == currentDays ? atNew : repository.countArchivable(tenantId, currentDays);

        return new CampaignArchivePreviewDto(
                currentDays, newDays,
                atNew.campaigns(), atNew.contacts(),
                atCurrent.campaigns(), atCurrent.contacts(),
                atNew.campaigns() - atCurrent.campaigns(),
                atNew.contacts() - atCurrent.contacts(),
                newDays < currentDays);
    }

    /** Obrona w głąb: ten sam zakres co Bean Validation i CHECK w bazie (V136). */
    private static int validate(Integer days) {
        if (days == null) {
            throw new IllegalArgumentException("Okres archiwizacji (dni) jest wymagany");
        }
        if (days < MIN_ARCHIVE_AFTER_DAYS || days > MAX_ARCHIVE_AFTER_DAYS) {
            throw new IllegalArgumentException(
                    "Okres archiwizacji musi mieścić się w zakresie %d-%d dni (podano: %d)"
                            .formatted(MIN_ARCHIVE_AFTER_DAYS, MAX_ARCHIVE_AFTER_DAYS, days));
        }
        return days;
    }

    private static CampaignArchiveSettingDto toDto(Optional<TenantCampaignArchiveSetting> setting) {
        return setting.map(CampaignArchiveSettingServiceImpl::toDto).orElseGet(CampaignArchiveSettingDto::defaults);
    }

    private static CampaignArchiveSettingDto toDto(TenantCampaignArchiveSetting s) {
        return CampaignArchiveSettingDto.explicit(s.getArchiveAfterDays(), s.getUpdatedBy(), s.getUpdatedAt());
    }

    private void publishAudit(UUID tenantId, UUID userId, boolean hadExplicitValue,
                              int oldDays, int newDays, boolean shortened) {
        try {
            auditLogService.publishAuditEvent(new AuditLogEvent(
                    tenantId,
                    userId,
                    AUDIT_ACTION,
                    AUDIT_ENTITY_TYPE,
                    tenantId,
                    "{\"archiveAfterDays\":" + oldDays + ",\"isDefault\":" + !hadExplicitValue + "}",
                    "{\"archiveAfterDays\":" + newDays + ",\"shortened\":" + shortened + "}",
                    null,
                    null,
                    Instant.now()
            ));
        } catch (Exception e) {
            log.error("[CampaignArchiveSetting] UTRATA AUDYTU zmiany okresu archiwizacji ({} -> {} dni): "
                            + "tenant={}, user={}, error={}", oldDays, newDays, tenantId, userId, e.getMessage(), e);
        }
    }
}
