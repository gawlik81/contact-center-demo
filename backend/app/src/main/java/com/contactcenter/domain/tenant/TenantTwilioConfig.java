package com.contactcenter.domain.tenant;

import com.contactcenter.infrastructure.persistence.converter.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_twilio_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantTwilioConfig {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "config_id", updatable = false, nullable = false)
    private UUID configId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "account_sid", nullable = false, length = 255)
    private String accountSid;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "auth_token", nullable = false)
    private String authToken;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "api_key_sid", length = 255)
    private String apiKeySid;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "api_key_secret")
    private String apiKeySecret;

    @Column(name = "twiml_app_sid", length = 64)
    private String twimlAppSid;

    @Column(name = "phone_number", length = 30)
    private String phoneNumber;

    @Column(name = "status_callback_url")
    private String statusCallbackUrl;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
