package com.contactcenter.domain.disposition.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Żądanie utworzenia nowego zestawu dyspozycji.
 */
public record CreateDispositionSetRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 500) String description
) {}
