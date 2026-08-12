package com.contactcenter.domain.retention.dto;

import com.contactcenter.domain.retention.PurgeTriggerType;
import com.contactcenter.domain.retention.RetentionDataCategory;
import com.contactcenter.domain.retention.RetentionPurgeLog;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Reprezentacja wiersza historii operacji purge do ekspozycji poza pakiet {@code domain.retention}
 * (przyszły status endpoint {@code GET .../purge/{purgeId}} oraz historia {@code GET .../history}, BE-118).
 *
 * <p>Niemutowalny DTO — encja JPA {@link RetentionPurgeLog} nigdy nie opuszcza pakietu
 * {@code domain.retention}, analogicznie do {@code RetentionPolicyDto} wprowadzonego w BE-111.
 *
 * @param purgeId      identyfikator operacji purge
 * @param tenantId     identyfikator tenanta-właściciela
 * @param dataCategory kategoria danych objęta purge
 * @param triggerType  MANUAL lub AUTO
 * @param triggeredBy  UUID użytkownika, który wywołał purge ręcznie (null dla AUTO)
 * @param cutoffDate   granica czasowa — wiersze starsze zostały (są) usuwane
 * @param rowsDeleted  suma usuniętych wierszy (null dopóki status=RUNNING)
 * @param status       RUNNING, COMPLETED lub FAILED
 * @param startedAt    znacznik czasu rozpoczęcia
 * @param completedAt  znacznik czasu zakończenia (null dopóki RUNNING)
 * @param errorMessage komunikat błędu przy status=FAILED (null przy sukcesie)
 */
public record PurgeResultDto(
        UUID purgeId,
        UUID tenantId,
        RetentionDataCategory dataCategory,
        PurgeTriggerType triggerType,
        UUID triggeredBy,
        LocalDate cutoffDate,
        Long rowsDeleted,
        String status,
        Instant startedAt,
        Instant completedAt,
        String errorMessage
) {
    public static PurgeResultDto from(RetentionPurgeLog entity) {
        return new PurgeResultDto(
                entity.getPurgeId(),
                entity.getTenantId(),
                entity.getDataCategory(),
                entity.getTriggerType(),
                entity.getTriggeredBy(),
                entity.getCutoffDate(),
                entity.getRowsDeleted(),
                entity.getStatus(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getErrorMessage()
        );
    }
}
