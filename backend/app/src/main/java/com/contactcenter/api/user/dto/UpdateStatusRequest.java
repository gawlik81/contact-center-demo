package com.contactcenter.api.user.dto;

import com.contactcenter.domain.user.AppUser.UserStatus;
import jakarta.validation.constraints.NotNull;

/**
 * Żądanie zmiany statusu agenta.
 *
 * <p>Używane przez endpoint {@code PATCH /api/users/{id}/status}.
 * Dozwolone statusy: AVAILABLE, BUSY, BREAK, AFTER_CONTACT.
 * Statusy ACTIVE i INACTIVE są zarządzane przez CRUD użytkownika, nie przez ten endpoint.
 */
public record UpdateStatusRequest(

        @NotNull(message = "Status jest wymagany")
        UserStatus status
) {}
