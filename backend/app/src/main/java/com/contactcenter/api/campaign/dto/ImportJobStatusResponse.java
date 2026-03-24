package com.contactcenter.api.campaign.dto;

import com.contactcenter.domain.model.ImportJobStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO odpowiedzi dla statusu asynchronicznego joba importu CSV.
 *
 * <p>Używany przez {@code GET /api/campaigns/{id}/import-status/{jobId}}.
 *
 * @param jobId          UUID joba importu
 * @param campaignId     UUID kampanii do której importowano
 * @param status         aktualny status joba: QUEUED, PROCESSING, COMPLETED, FAILED_PARTIAL
 * @param totalRows      łączna liczba wierszy danych w pliku CSV (bez nagłówka)
 * @param processedRows  liczba wierszy już przetworzonych
 * @param importedRows   liczba wierszy pomyślnie zaimportowanych
 * @param rejectedRows   liczba wierszy odrzuconych (błędny format telefonu)
 * @param rejectedSamples pierwsze (max 10) przykłady odrzuconych wierszy z powodem
 * @param startedAt      czas rozpoczęcia przetwarzania (null gdy QUEUED)
 * @param completedAt    czas zakończenia (null gdy job jeszcze trwa)
 */
@Schema(description = "Status asynchronicznego joba importu CSV kontaktów kampanii")
public record ImportJobStatusResponse(

        @Schema(description = "UUID joba importu", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID jobId,

        @Schema(description = "UUID kampanii", example = "550e8400-e29b-41d4-a716-446655440001")
        UUID campaignId,

        @Schema(description = "Status joba: QUEUED | PROCESSING | COMPLETED | FAILED_PARTIAL",
                example = "PROCESSING")
        String status,

        @Schema(description = "Łączna liczba wierszy danych w pliku (bez nagłówka)", example = "10000")
        int totalRows,

        @Schema(description = "Liczba wierszy już przetworzonych", example = "5000")
        int processedRows,

        @Schema(description = "Liczba pomyślnie zaimportowanych rekordów", example = "4980")
        int importedRows,

        @Schema(description = "Liczba odrzuconych wierszy (błędny telefon)", example = "20")
        int rejectedRows,

        @Schema(description = "Przykłady odrzuconych wierszy (max 10)",
                example = "[\"row 3: invalid phone +48abc\", \"row 7: invalid phone 123456\"]")
        List<String> rejectedSamples,

        @Schema(description = "Czas rozpoczęcia przetwarzania (ISO 8601)", example = "2026-03-24T10:00:00Z")
        Instant startedAt,

        @Schema(description = "Czas zakończenia (null gdy trwa)", example = "2026-03-24T10:01:30Z")
        Instant completedAt

) {

    /**
     * Tworzy DTO z modelu domenowego {@link ImportJobStatus}.
     *
     * @param model model statusu joba z Redis
     * @return DTO gotowe do serializacji
     */
    public static ImportJobStatusResponse from(ImportJobStatus model) {
        return new ImportJobStatusResponse(
                model.getJobId(),
                model.getCampaignId(),
                model.getStatus() != null ? model.getStatus().name() : null,
                model.getTotalRows(),
                model.getProcessedRows(),
                model.getImportedRows(),
                model.getRejectedRows(),
                model.getRejectedSamples() != null ? model.getRejectedSamples() : List.of(),
                model.getStartedAt(),
                model.getCompletedAt()
        );
    }
}
