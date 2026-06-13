package com.contactcenter.domain.tenant;

import com.contactcenter.domain.model.AiProvider;
import com.contactcenter.infrastructure.persistence.converter.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.dialect.PostgreSQLEnumJdbcType;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenant_ai_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantAiConfig {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @JdbcType(PostgreSQLEnumJdbcType.class)
    @Column(name = "provider", nullable = false, columnDefinition = "ai_provider")
    private AiProvider provider;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "api_key_encrypted", nullable = false)
    private String apiKeyEncrypted;

    @Column(name = "model_name", length = 100, nullable = false)
    private String modelName;

    @Column(name = "azure_endpoint", length = 500)
    private String azureEndpoint;

    @Column(name = "azure_deployment_name", length = 100)
    private String azureDeploymentName;

    @Column(name = "summary_prompt_template", columnDefinition = "TEXT")
    private String summaryPromptTemplate;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
