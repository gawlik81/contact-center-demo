package com.contactcenter.domain.disposition.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Żądanie aktualizacji elementu zestawu dyspozycji.
 * Kod dyspozycji ({@code dispositionCode}) jest niezmienny po utworzeniu — brak go w tym DTO.
 */
public record UpdateDispositionSetItemRequest(
        @NotBlank @Size(max = 100) String label,
        @NotNull @Pattern(regexp = "positive|negative|neutral|warning") String tone,
        @Min(0) int ordinal
) {}
