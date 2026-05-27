package com.contactcenter.domain.disposition.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Pełny widok dyspozycji dla supervisora.
 *
 * <p>Zawiera wszystkie pola zarządcze (id, is_active, created_at) używane
 * w panelu administracyjnym do zarządzania zestawem dyspozycji per kampania/kolejka.
 */
public record CustomDispositionDto(
        UUID id,
        String dispositionCode,
        String label,
        String tone,
        int ordinal,
        boolean isActive,
        Instant createdAt
) {}
