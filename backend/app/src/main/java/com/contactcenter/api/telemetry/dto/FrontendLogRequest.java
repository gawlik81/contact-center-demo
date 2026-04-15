package com.contactcenter.api.telemetry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO reprezentujący pojedynczy wpis logu wysłany przez frontend.
 *
 * <p>Frontend wysyła tablicę tych obiektów do {@code POST /api/logs}.
 * Endpoint jest publiczny – FE może logować błędy nawet przed zalogowaniem.
 *
 * @param level      poziom logu: ERROR, WARN, INFO, DEBUG
 * @param message    treść komunikatu (max 2000 znaków)
 * @param logger     nazwa loggera w aplikacji frontendowej (max 255 znaków)
 * @param stackTrace opcjonalny stack trace (max 5000 znaków)
 * @param tenantId   opcjonalne UUID tenantu jako string – tylko do logowania, NIE do autoryzacji
 * @param userId     opcjonalne UUID użytkownika jako string – tylko do logowania, NIE do autoryzacji
 * @param timestamp  czas zdarzenia w epoch milliseconds
 */
public record FrontendLogRequest(
        @NotBlank String level,
        @NotBlank @Size(max = 2000) String message,
        @NotBlank @Size(max = 255) String logger,
        @Size(max = 5000) String stackTrace,
        String tenantId,
        String userId,
        long timestamp
) {}
