package com.contactcenter.domain.disposition.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Żądanie dodania nowego elementu do zestawu dyspozycji.
 */
public record CreateDispositionSetItemRequest(
        @NotBlank @Size(max = 50) @Pattern(regexp = "^[A-Z0-9_]+$") String dispositionCode,
        @NotBlank @Size(max = 100) String label,
        @NotNull @Pattern(regexp = "positive|negative|neutral|warning") String tone,
        @Min(0) int ordinal
) {}
