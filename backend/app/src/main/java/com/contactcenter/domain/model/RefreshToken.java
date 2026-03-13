package com.contactcenter.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Encja przechowująca refresh tokeny w bazie danych.
 *
 * <p>Mapuje tabelę {@code refresh_token} (schemat z DB-001).
 *
 * <p>Strategia bezpieczeństwa:
 * <ul>
 *   <li>Token to bezpieczny losowy ciąg (UUID v4) – nieprzewidywalny</li>
 *   <li>Po użyciu token jest unieważniany (rotation), nowy token jest wystawiany</li>
 *   <li>Token może być jawnie unieważniony przy logout ({@code isRevoked=true})</li>
 *   <li>Sprawdzamy: token istnieje AND NOT revoked AND expires_at &gt; now()</li>
 * </ul>
 */
@Entity
@Table(name = "refresh_token")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "token_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /**
     * Unikalny raw token (bezpieczny UUID random). Mapuje kolumnę {@code token_hash}
     * (dodaną w V018 jako alias raw tokenu – zgodnie z podejściem aplikacyjnym BE-003).
     */
    @Column(name = "token_hash", nullable = false, unique = true)
    private String token;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "is_revoked", nullable = false)
    private boolean revoked;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /** Sprawdza czy token jest aktywny (nie wygasł i nie unieważniony). */
    public boolean isValid() {
        return !revoked && Instant.now().isBefore(expiresAt);
    }
}
