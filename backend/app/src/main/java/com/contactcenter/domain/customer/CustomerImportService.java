package com.contactcenter.domain.customer;

import com.contactcenter.api.customer.DeduplicationMode;
import com.contactcenter.api.customer.dto.CustomerImportStatusResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Serwis obsługujący asynchroniczny import klientów z pliku CSV (BE-026).
 *
 * <p>Przepływ:
 * <ol>
 *   <li>{@link #initiateImport} – waliduje plik, tworzy rekord statusu w Redis (QUEUED),
 *       pobiera snapshot TenantContext, uruchamia asynchroniczne przetwarzanie w tle.</li>
 *   <li>Asynchroniczne przetwarzanie – parsuje CSV, waliduje telefony, batch-insertuje
 *       do tabeli {@code customer}, deduplikuje po phone lub email (SKIP lub OVERWRITE),
 *       aktualizuje status co chunk. Na końcu: COMPLETED lub FAILED_PARTIAL.</li>
 * </ol>
 *
 * <p>Status joba przechowywany w Redis pod kluczem {@code import:customer:}{jobId}
 * z TTL 1h.
 *
 * <p>Kolumny CSV: first_name, last_name, phone (wielokrotne wartości rozdzielone ';'),
 * email (wielokrotne wartości rozdzielone ';'), custom_fields.
 *
 * <p>Walidacja telefonu: format E.164 {@code +[1-15 cyfr]}.
 */
public interface CustomerImportService {

    /**
     * Waliduje plik i inicjuje asynchroniczny import klientów.
     *
     * @param file              plik CSV (multipart)
     * @param deduplicationMode strategia deduplikacji
     * @param columnSeparator   separator kolumn
     * @param quoteChar         znak cytowania
     * @param columnMappingJson mapowanie kolumn CSV → pole systemu (JSON string) lub null
     * @return UUID joba – do pollingu statusu
     * @throws IllegalArgumentException gdy plik nie spełnia wymagań
     */
    UUID initiateImport(MultipartFile file, DeduplicationMode deduplicationMode,
                         String columnSeparator, String quoteChar, String columnMappingJson);

    /**
     * Pobiera status joba z Redis jako DTO do kontrolera.
     *
     * @param jobId UUID joba
     * @return DTO statusu lub null gdy nie istnieje
     */
    CustomerImportStatusResponse getJobStatus(UUID jobId);

    /**
     * Pobiera raport błędów jako CSV bytes z Redis.
     *
     * @param jobId UUID joba
     * @return bytes CSV lub null gdy brak błędów / job nieznany
     */
    byte[] getErrorReport(UUID jobId);
}
