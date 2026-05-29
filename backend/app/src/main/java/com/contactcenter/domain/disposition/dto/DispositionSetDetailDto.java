package com.contactcenter.domain.disposition.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO widoku szczegółów zestawu dyspozycji — zawiera pełną listę elementów.
 * Używany w endpointach pobierania pojedynczego zestawu.
 */
public record DispositionSetDetailDto(
        UUID id,
        String name,
        String description,
        List<DispositionSetItemDto> items,
        Instant createdAt
) {}
