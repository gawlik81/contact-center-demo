package com.contactcenter.domain.plugin;

import com.contactcenter.infrastructure.persistence.JsonStringListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Encja reprezentująca jedną, niemutowalną wersję JAR-a pluginu.
 *
 * <p>Mapuje tabelę {@code plugin_version} (DB-042/V074__create_plugin_catalog.sql).
 * Tak jak {@link Plugin}, tabela jest globalna (bez {@code tenant_id}, bez RLS).
 *
 * <p>Wiersz jest tworzony przez BE-099 (poza zakresem tego ticketu) po pozytywnej
 * walidacji przez {@link PluginValidationService} – ten ticket (BE-098) dostarcza
 * wyłącznie mapowanie JPA gotowe na użycie przez BE-099, bez logiki zapisu.
 */
@Entity
@Table(name = "plugin_version")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class PluginVersion {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "plugin_id", nullable = false)
    private Plugin plugin;

    @Column(name = "version", nullable = false, length = 50)
    private String version;

    @Column(name = "jar_object_key", nullable = false, length = 500)
    private String jarObjectKey;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    /**
     * Pełny sparsowany {@code META-INF/plugin-manifest.json}, przechowywany dla audytu/replay
     * (komentarz V074). Mapowany jako {@code Map<String, Object>} JSONB, tak jak
     * {@code Tenant.config}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manifest_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> manifestJson;

    @Column(name = "sdk_version", nullable = false, length = 20)
    private String sdkVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private PluginVersionStatus status = PluginVersionStatus.UPLOADED;

    /**
     * Lista błędów walidacji opisowych (puste/{@code null} gdy {@code status != REJECTED}).
     */
    @Convert(converter = JsonStringListConverter.class)
    @Column(name = "validation_errors", columnDefinition = "jsonb")
    private List<String> validationErrors;

    @Column(name = "uploaded_by_user_id")
    private UUID uploadedByUserId;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    /**
     * Status wersji pluginu — odpowiada CHECK constraint {@code chk_plugin_version_status}
     * w V074 (UPLOADED, VALIDATED, PENDING_REVIEW, REJECTED, REVOKED).
     */
    public enum PluginVersionStatus {
        UPLOADED,
        VALIDATED,
        PENDING_REVIEW,
        REJECTED,
        REVOKED
    }
}
