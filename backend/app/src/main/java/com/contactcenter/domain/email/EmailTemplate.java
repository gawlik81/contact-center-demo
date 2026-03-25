package com.contactcenter.domain.email;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Encja JPA mapująca tabelę {@code email_template} (schemat z V028).
 *
 * <p>Szablon przechowuje treść email z placeholderami Mustache ({@code {{varName}}}).
 * Pole {@code variables} to lista nazw zmiennych wymaganych przez szablon –
 * służy do walidacji przy renderowaniu.
 *
 * <p>Stosuje soft delete przez {@code is_active = false} zamiast fizycznego usunięcia
 * (zgodnie ze schematem V010 który używa is_active zamiast is_deleted).
 */
@Entity
@Table(name = "email_template")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailTemplate {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "template_id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** Nazwa szablonu – unikalna w ramach tenanta (aktywne rekordy). */
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** Temat wiadomości – może zawierać placeholdery Mustache. */
    @Column(name = "subject_template", nullable = false, length = 998)
    private String subjectTemplate;

    /** Treść HTML wiadomości – może zawierać placeholdery Mustache. */
    @Column(name = "body_html", nullable = false, columnDefinition = "TEXT")
    private String bodyHtml;

    /**
     * Lista nazw zmiennych wymaganych przez szablon (np. ["customerName", "agentName"]).
     * Przechowywana jako JSONB array – walidacja przy renderowaniu.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variables", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> variables = new ArrayList<>();

    /**
     * Soft delete – szablon nieaktywny (is_active=false) zamiast fizycznego usunięcia.
     * Schemat V010 używa is_active (konwencja odwrotna do is_deleted).
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (variables == null) {
            variables = new ArrayList<>();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
