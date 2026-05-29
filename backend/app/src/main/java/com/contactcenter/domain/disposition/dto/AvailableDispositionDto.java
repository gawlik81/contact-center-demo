package com.contactcenter.domain.disposition.dto;

/**
 * DTO dyspozycji eksponowanej agentowi po zakończeniu kontaktu.
 *
 * <p>Zawiera tylko pola niezbędne do wyboru dyspozycji — bez danych zarządczych
 * (is_active, created_at, id). Zwracany przez endpoint resolucji dyspozycji.
 */
public record AvailableDispositionDto(
        String dispositionCode,
        String label,
        String tone,
        int ordinal
) {}
