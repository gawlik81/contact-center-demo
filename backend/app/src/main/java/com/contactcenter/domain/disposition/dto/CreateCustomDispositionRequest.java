package com.contactcenter.domain.disposition.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Żądanie utworzenia nowej dyspozycji.
 *
 * <p>Kod dyspozycji ({@code dispositionCode}) jest niezmienny po utworzeniu —
 * musi być unikalny w zakresie kampanii lub kolejki.
 * Dopuszczalne znaki: wielkie litery A-Z, cyfry 0-9 i podkreślenie.
 */
public record CreateCustomDispositionRequest(
        @NotBlank @Size(max = 50) @Pattern(regexp = "^[A-Z0-9_]+$") String dispositionCode,
        @NotBlank @Size(max = 100) String label,
        @NotNull @Pattern(regexp = "positive|negative|neutral|warning") String tone,
        int ordinal
) {}
