package com.contactcenter.domain.disposition.dto;

/**
 * Odpowiedź operacji zastosowania zestawu dyspozycji do kampanii lub kolejki.
 *
 * @param copied  liczba pomyślnie skopiowanych dyspozycji
 * @param skipped liczba pominiętych dyspozycji (duplikat kodu)
 * @param message czytelny komunikat podsumowujący wynik operacji
 */
public record ApplySetResponse(
        int copied,
        int skipped,
        String message
) {}
