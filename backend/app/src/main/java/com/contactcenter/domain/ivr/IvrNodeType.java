package com.contactcenter.domain.ivr;

/**
 * Typy węzłów drzewa IVR.
 *
 * <ul>
 *   <li>{@code MENU}           – węzeł z opcjami wyboru DTMF (oczekuje na naciśnięcie klawisza)</li>
 *   <li>{@code PLAY_AUDIO}     – odtworzenie pliku audio z S3 lub generacja TTS</li>
 *   <li>{@code COLLECT_DTMF}  – zbieranie wejścia DTMF od dzwoniącego z timeoutem</li>
 *   <li>{@code QUEUE_TRANSFER} – przekazanie do kolejki obsługi przez agenta</li>
 *   <li>{@code HANGUP}         – rozłączenie połączenia</li>
 *   <li>{@code SET}            – ustawia zmienną sesji na podaną wartość, natychmiast przechodzi do {@code next}</li>
 *   <li>{@code IF}             – testuje zmienną warunkowo; wyjścia: {@code true} / {@code false}</li>
 *   <li>{@code SWITCH}         – routing wielowariantowy na podstawie wartości zmiennej; fallback: {@code default}</li>
 * </ul>
 */
public enum IvrNodeType {
    MENU,
    PLAY_AUDIO,
    COLLECT_DTMF,
    QUEUE_TRANSFER,
    HANGUP,
    SET,
    IF,
    SWITCH
}
