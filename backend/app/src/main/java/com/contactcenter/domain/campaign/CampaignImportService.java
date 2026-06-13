package com.contactcenter.domain.campaign;

import com.contactcenter.domain.model.ImportJobStatus;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Serwis obsługujący asynchroniczny import kontaktów kampanii z pliku CSV.
 *
 * <p>Implementuje BE-023: Import CSV kontaktów kampanii (async job).
 *
 * <p>Przepływ:
 * <ol>
 *   <li>{@code initiateImport} – waliduje plik, tworzy rekord statusu w Redis (QUEUED),
 *       pobiera snapshot TenantContext, uruchamia przetwarzanie w tle.</li>
 *   <li>Przetwarzanie asynchroniczne – parsuje CSV, waliduje telefony, batch-insertuje
 *       do {@code campaign_contact} (chunk po 1000 rekordów),
 *       aktualizuje status co chunk. Na końcu: COMPLETED lub FAILED_PARTIAL.</li>
 * </ol>
 *
 * <p>Status joba przechowywany w Redis pod kluczem {@code import:job:}{jobId}
 * z TTL 3600 sekund (1h).
 *
 * <p>Format CSV: nagłówek {@code phone,first_name,last_name,custom_field_1,custom_field_2}
 * lub bez nagłówka (kolumna 0=phone, 1=first_name, 2=last_name).
 *
 * <p>Walidacja telefonu: format E.164 {@code +[1-15 cyfr]}.
 */
public interface CampaignImportService {

    /**
     * Waliduje plik i inicjuje asynchroniczny import.
     *
     * <p>Wywołanie synchroniczne – szybkie (walidacja + zapis do Redis + start @Async).
     * Zwraca jobId natychmiast, bez czekania na zakończenie importu.
     *
     * @param campaignId     UUID kampanii (musi należeć do tenanta z TenantContext)
     * @param file           plik CSV (multipart)
     * @param skipDuplicates true = pomiń duplikaty po telefonie (domyślnie true)
     * @return UUID jobu – do pollingu statusu
     * @throws IllegalArgumentException  gdy plik jest za duży lub ma złe rozszerzenie
     * @throws EntityNotFoundException   gdy kampania nie istnieje lub należy do innego tenanta
     */
    UUID initiateImport(UUID campaignId, MultipartFile file, boolean skipDuplicates,
                         String columnSeparator, String quoteChar, String columnMappingJson);

    /**
     * Pobiera status joba z Redis.
     *
     * @param jobId UUID joba
     * @return status joba lub null gdy nie istnieje
     */
    ImportJobStatus getJobStatus(UUID jobId);
}
