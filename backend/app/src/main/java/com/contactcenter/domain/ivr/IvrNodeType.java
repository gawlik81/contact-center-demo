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
 * </ul>
 */
public enum IvrNodeType {
    MENU,
    PLAY_AUDIO,
    COLLECT_DTMF,
    QUEUE_TRANSFER,
    HANGUP
}
