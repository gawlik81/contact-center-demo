package com.contactcenter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

/**
 * Encja reprezentująca użytkownika systemu Contact Center.
 *
 * <p>Mapuje tabelę {@code app_user} (schemat z DB-001).
 * Obsługuje multi-tenancy przez pole {@code tenantId} – każdy użytkownik
 * należy do dokładnie jednego tenanta.
 *
 * <p>Pola bezpieczeństwa:
 * <ul>
 *   <li>{@code passwordHash} – bcrypt cost=12 (nigdy nie przechowujemy plain text)</li>
 *   <li>{@code mfaSecret} – TOTP secret (Base32 encoded, NULL gdy MFA nie skonfigurowane)</li>
 *   <li>{@code mfaEnabled} – true gdy użytkownik ukończył setup MFA i zweryfikował kod</li>
 * </ul>
 */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"passwordHash", "mfaSecret"})
public class AppUser {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "user_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "email", nullable = false)
    private String email;

    /** Bcrypt hash hasła (cost=12). Nigdy nie eksponować w DTO/logach. */
    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private UserRole role;

    /**
     * Czy konto jest aktywne. Mapuje kolumnę {@code is_active} (dodaną w V018).
     * Konto nieaktywne ({@code false}) nie może się zalogować.
     */
    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "mfa_enabled", nullable = false)
    private boolean mfaEnabled;

    /**
     * TOTP secret zakodowany w Base32.
     * NULL gdy MFA nie zostało skonfigurowane przez użytkownika.
     * Długość 32 znaki (160 bitów = 20 bajtów w Base32).
     */
    @Column(name = "mfa_secret", length = 32)
    private String mfaSecret;

    @Column(name = "password_reset_required", nullable = false)
    private boolean passwordResetRequired;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserStatus status;

    /** Wyliczona rola Spring Security – alias dla pola role. */
    public String getRoleName() {
        return role != null ? role.name() : null;
    }

    // =========================================================================
    // Enumy zgodne ze schematem DB-001
    // =========================================================================

    public enum UserRole {
        ADMIN, SUPERVISOR, AGENT
    }

    public enum UserStatus {
        ACTIVE, INACTIVE, BREAK, AVAILABLE, BUSY, AFTER_CONTACT
    }
}
