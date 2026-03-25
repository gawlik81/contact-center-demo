package com.contactcenter.domain.ivr;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Węzeł drzewa IVR.
 *
 * <p>Reprezentuje pojedynczy krok przepływu IVR. Typ węzła ({@link IvrNodeType}) decyduje
 * o logice wykonania. Pola opcjonalne (audioId, options, queueId) są używane zależnie od typu.
 *
 * @param nodeId         unikalny identyfikator węzła (UUID jako string)
 * @param type           typ węzła – decyduje o zachowaniu silnika IVR
 * @param prompt         tekst komunikatu (fallback gdy brak audioId, lub dla TTS)
 * @param audioId        UUID pliku audio z tabeli ivr_audio (opcjonalne)
 * @param options        opcje wyboru DTMF (dla MENU i COLLECT_DTMF)
 * @param queueId        UUID kolejki docelowej (dla QUEUE_TRANSFER)
 * @param timeoutSeconds czas oczekiwania na wejście DTMF w sekundach (domyślnie 10)
 * @param maxRetries     maksymalna liczba prób ponownego odtworzenia komunikatu (domyślnie 3)
 * @param variableName   nazwa zmiennej sesji, do której zapisywane są zebrane cyfry (COLLECT_DTMF)
 * @param minDigits      minimalna liczba cyfr wymagana do zakończenia zbierania (domyślnie 1)
 * @param maxDigits      maksymalna liczba cyfr – po osiągnięciu auto-zakończenie (domyślnie 1)
 * @param finishOnKey    klawisz kończący zbieranie cyfr (domyślnie "#"; pusty string = auto po maxDigits)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IvrNode(
        @JsonProperty("node_id") String nodeId,
        @JsonProperty("type") IvrNodeType type,
        @JsonProperty("prompt") String prompt,
        @JsonProperty("audio_id") String audioId,
        @JsonProperty("options") List<IvrOption> options,
        @JsonProperty("queue_id") String queueId,
        @JsonProperty("timeout_seconds") int timeoutSeconds,
        @JsonProperty("max_retries") int maxRetries,
        @JsonProperty("variable_name") String variableName,
        @JsonProperty("min_digits") int minDigits,
        @JsonProperty("max_digits") int maxDigits,
        @JsonProperty("finish_on_key") String finishOnKey
) {
    /**
     * Wartości domyślne dla pól opcjonalnych.
     */
    public IvrNode {
        if (timeoutSeconds <= 0) timeoutSeconds = 10;
        if (maxRetries <= 0)     maxRetries = 3;
        if (minDigits <= 0)      minDigits = 1;
        if (maxDigits <= 0)      maxDigits = 1;
        if (finishOnKey == null) finishOnKey = "#";
    }

    /**
     * Wyszukuje opcję odpowiadającą podanemu kluczowi DTMF.
     *
     * @param key klawisz DTMF lub specjalna wartość ("timeout", "no-input")
     * @return opcja lub null gdy nie znaleziono
     */
    public IvrOption findOption(String key) {
        if (options == null || key == null) return null;
        return options.stream()
                .filter(opt -> key.equals(opt.key()))
                .findFirst()
                .orElse(null);
    }
}
