package com.contactcenter.domain.social;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Encja JPA mapująca tabelę {@code social_integration} (schemat z V010__create_email_social.sql).
 *
 * <p>Tabela przechowuje konfigurację połączeń z platformami social media per tenant.
 * Token dostępu jest przechowywany wyłącznie w formie zaszyfrowanej (AES-256-GCM)
 * w polu {@code access_token_encrypted} (BYTEA) – nigdy w plaintext.
 *
 * <p>Kolumna {@code platform} mapuje typ ENUM {@code social_platform}.
 * Ponieważ typy ENUM w tym projekcie są zachowane dla tabeli social_integration
 * (konwersja do VARCHAR dotyczyła tylko contact/campaign), używamy
 * {@code columnDefinition = "social_platform"}.
 */
@Entity
@Table(name = "social_integration")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SocialIntegration {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "integration_id", updatable = false, nullable = false)
    private UUID integrationId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, columnDefinition = "social_platform")
    private SocialPlatform platform;

    @Column(name = "page_id", nullable = false, length = 255)
    private String pageId;

    @Column(name = "display_name", length = 255)
    private String displayName;

    /**
     * Token dostępu zaszyfrowany AES-256-GCM.
     * Format: [IV (12B)][ciphertext+GCM-tag]. Nigdy nie przechowuj w plaintext.
     */
    @Column(name = "access_token_encrypted", columnDefinition = "BYTEA")
    private byte[] accessTokenEncrypted;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(name = "webhook_url", columnDefinition = "TEXT")
    private String webhookUrl;

    /**
     * Status webhooka: ACTIVE, INACTIVE, ERROR, EXPIRED_TOKEN.
     * Zgodny z constraint CHECK w tabeli.
     */
    @Column(name = "webhook_status", length = 20, nullable = false)
    @Builder.Default
    private String webhookStatus = "ACTIVE";

    @Column(name = "platform_config", columnDefinition = "JSONB")
    @Builder.Default
    private String platformConfig = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (platformConfig == null) {
            platformConfig = "{}";
        }
        if (webhookStatus == null) {
            webhookStatus = "ACTIVE";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
