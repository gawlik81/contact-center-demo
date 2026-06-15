package com.contactcenter.domain.campaign;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Model statusu asynchronicznego jobu importu CSV kontaktów kampanii.
 *
 * <p>Przechowywany w Redis pod kluczem {@code import:job:{jobId}} (TTL 1h).
 * Pole {@code status} przechodzi przez stany: QUEUED → PROCESSING → COMPLETED / FAILED_PARTIAL.
 *
 * <p>Implementuje {@link Serializable} – wymagane przez GenericJackson2JsonRedisSerializer.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class ImportJobStatus implements Serializable {

    /**
     * Dopuszczalne statusy jobu importu.
     */
    public enum Status {
        /** Job zarejestrowany, oczekuje na przetworzenie. */
        QUEUED,
        /** Job aktualnie przetwarzany (chunki w toku). */
        PROCESSING,
        /** Import zakończony, wszystkie wiersze przetworzone. */
        COMPLETED,
        /**
         * Import zakończony, ale część wierszy odrzucona (np. błędny telefon).
         * Liczba odrzuconych wierszy widoczna w {@link #rejectedRows}.
         */
        FAILED_PARTIAL
    }

    /** UUID jobu – generowany przy przyjęciu pliku. */
    private UUID jobId;

    /** UUID kampanii, do której importowane są kontakty. */
    private UUID campaignId;

    /** Aktualny status jobu. */
    private Status status;

    /** Łączna liczba wierszy w pliku CSV (bez nagłówka). */
    private int totalRows;

    /** Liczba wierszy już przetworzonych (zaktualizowana co chunk). */
    private int processedRows;

    /** Liczba wierszy pomyślnie zaimportowanych (bez odrzuconych i duplikatów). */
    private int importedRows;

    /** Liczba wierszy odrzuconych (błędny telefon, problem parsowania). */
    private int rejectedRows;

    /**
     * Przykłady odrzuconych wierszy (max 10 pozycji).
     * Format: "row {N}: {powód odrzucenia}".
     */
    @Builder.Default
    private List<String> rejectedSamples = new ArrayList<>();

    /** Czas uruchomienia przetwarzania (kiedy job przeszedł w PROCESSING). */
    private Instant startedAt;

    /** Czas zakończenia przetwarzania (null gdy job jeszcze trwa). */
    private Instant completedAt;

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Dodaje próbkę odrzuconego wiersza (max 10 próbek).
     *
     * @param sample opis odrzuconego wiersza, np. "row 3: invalid phone +48abc"
     */
    public void addRejectedSample(String sample) {
        if (rejectedSamples == null) {
            rejectedSamples = new ArrayList<>();
        }
        if (rejectedSamples.size() < 10) {
            rejectedSamples.add(sample);
        }
    }

    /**
     * Sprawdza czy job jest w stanie końcowym (COMPLETED lub FAILED_PARTIAL).
     */
    public boolean isFinished() {
        return status == Status.COMPLETED || status == Status.FAILED_PARTIAL;
    }
}
