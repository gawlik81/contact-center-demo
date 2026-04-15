package com.contactcenter.api.customer.dto;

/**
 * DTO statusu asynchronicznego joba importu klientów z CSV (BE-026).
 *
 * <p>Stany {@code status}:
 * <ul>
 *   <li>QUEUED      – job zarejestrowany, oczekuje na przetworzenie</li>
 *   <li>PROCESSING  – import w toku (processed/total pokazuje postęp)</li>
 *   <li>COMPLETED   – import zakończony sukcesem</li>
 *   <li>FAILED_PARTIAL – zakończony, ale część rekordów odrzucona</li>
 * </ul>
 *
 * @param jobId              UUID joba importu
 * @param status             aktualny stan joba
 * @param processed          liczba przetworzonych wierszy
 * @param total              łączna liczba wierszy danych
 * @param imported           liczba nowo wstawionych klientów
 * @param updated            liczba zaktualizowanych klientów (tryb OVERWRITE)
 * @param skipped            liczba pominiętych duplikatów (tryb SKIP)
 * @param failed             liczba odrzuconych wierszy (błąd walidacji)
 * @param errorFileAvailable czy dostępny jest plik błędów (GET .../errors)
 */
public record CustomerImportStatusResponse(
        String jobId,
        String status,
        int processed,
        int total,
        int imported,
        int updated,
        int skipped,
        int failed,
        boolean errorFileAvailable
) {}
