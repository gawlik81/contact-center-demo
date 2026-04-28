package com.contactcenter.domain.agentbreak;

/**
 * Status przerwy agenta.
 *
 * <p>Przechowywany w kolumnie {@code status} jako VARCHAR(20).
 * Konwersja enum ↔ String odbywa się w warstwie serwisowej.
 */
public enum BreakStatus {
    PLANNED,
    ACTIVE,
    COMPLETED,
    CANCELLED
}
