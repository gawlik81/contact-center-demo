package com.contactcenter.api.user.dto;

import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.user.AppUser.UserRole;
import com.contactcenter.domain.user.AppUser.UserStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Odpowiedź API z danymi użytkownika.
 *
 * <p>Nie zawiera pól wrażliwych: passwordHash, mfaSecret.
 * Zwracana przez wszystkie endpointy CRUD użytkownika.
 */
public record UserResponse(
        UUID id,
        UUID tenantId,
        String email,
        String firstName,
        String lastName,
        UserRole role,
        UserStatus status,
        boolean active,
        boolean mfaEnabled,
        boolean passwordResetRequired,
        List<String> skills,
        Instant lastLoginAt,
        Instant createdAt,
        Instant updatedAt,
        @Schema(description = "Start of the current agent status (ISO-8601 UTC). Null = unknown "
                + "(never changed since the feature was introduced).", nullable = true)
        Instant statusSince
) {

    /**
     * Mapuje encję {@link AppUser} na DTO odpowiedzi.
     * Nigdy nie eksponuje passwordHash ani mfaSecret.
     *
     * @param user encja użytkownika z bazy danych
     * @return DTO gotowe do zwrócenia przez API
     */
    public static UserResponse from(AppUser user) {
        return new UserResponse(
                user.getId(),
                user.getTenantId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getRole(),
                user.getStatus(),
                user.isActive(),
                user.isMfaEnabled(),
                user.isPasswordResetRequired(),
                user.getSkills() != null ? user.getSkills() : List.of(),
                user.getLastLoginAt(),
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getStatusChangedAt()
        );
    }
}
