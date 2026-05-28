package com.contactcenter.domain.disposition.dto;

import java.util.UUID;

/**
 * DTO pojedynczego elementu zestawu dyspozycji.
 */
public record DispositionSetItemDto(
        UUID id,
        String dispositionCode,
        String label,
        String tone,
        int ordinal
) {}
