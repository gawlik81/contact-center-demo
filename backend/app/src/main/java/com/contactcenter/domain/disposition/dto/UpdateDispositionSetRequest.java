package com.contactcenter.domain.disposition.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Żądanie aktualizacji istniejącego zestawu dyspozycji.
 */
public record UpdateDispositionSetRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description
) {}
