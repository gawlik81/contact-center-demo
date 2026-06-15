package com.contactcenter.domain.agentbreak;

/**
 * Typ przerwy agenta.
 *
 * <p>Przechowywany w kolumnie {@code break_type} jako VARCHAR(50).
 * Konwersja enum ↔ String odbywa się w warstwie serwisowej.
 */
public enum BreakType {
    LUNCH,
    SHORT_BREAK,
    TRAINING,
    MEETING,
    OTHER
}
