package com.contactcenter.domain.disposition.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Żądanie aktualizacji istniejącej dyspozycji.
 *
 * <p>{@code dispositionCode} jest niezmienny po utworzeniu — nie jest uwzględniany
 * w tym żądaniu. Umożliwia zmianę etykiety, tonu, kolejności i statusu aktywności.
 */
public record UpdateCustomDispositionRequest(
        @NotBlank @Size(max = 100) String label,
        @NotNull @Pattern(regexp = "positive|negative|neutral|warning") String tone,
        int ordinal,
        boolean isActive
) {}
