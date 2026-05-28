package com.contactcenter.domain.disposition.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO widoku listy zestawów dyspozycji — zawiera liczbę elementów ({@code itemCount})
 * zamiast pełnej listy. Używany w endpointach listowania zestawów.
 */
public record DispositionSetDto(
        UUID id,
        String name,
        String description,
        int itemCount,
        Instant createdAt
) {}
