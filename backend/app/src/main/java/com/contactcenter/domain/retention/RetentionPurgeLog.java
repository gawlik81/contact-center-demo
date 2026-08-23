package com.contactcenter.domain.retention;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Encja domenowa audytu operacji usuwania (purge) danych retencyjnych.
 *
 * <p>Mapuje tabelę {@code retention_purge_log} (V084, DB-048). W odróżnieniu od
 * {@link TenantRetentionPolicy} (jeden wiersz per tenant+kategoria) ta tabela ma WIELE
 * wierszy w czasie — każde wywołanie {@link RetentionPurgeService#purge} to nowy wiersz,
 * zaczynający w stanie {@code RUNNING} i kończący jako {@code COMPLETED}/{@code FAILED}.
 *
 * <p>Tabela NIE jest partycjonowana — zapis/odczyt przez natywny SQL w
 * {@link RetentionPurgeLogRepository}, identycznie do wzorca {@link TenantRetentionPolicy}/
 * {@link TenantRetentionPolicyRepository}. {@code purge_id} generowane po stronie Javy
 * ({@code UUID.randomUUID()}), nie przez {@code DEFAULT uuid_generate_v4()} w DB — pozwala to
 * zwrócić {@code purgeId} wywołującemu natychmiast, bez dodatkowego zapytania odczytującego
 * wygenerowany identyfikator (kryterium akceptacji BE-113: „purgeId zwrócony natychmiast").
 */
@Entity
@Table(name = "retention_purge_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetentionPurgeLog {

    @Id
    @Column(name = "purge_id")
    private UUID purgeId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** Kategoria danych — wartości zgodne z CHECK w DB, patrz {@link RetentionDataCategory}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "data_category", nullable = false, length = 30, updatable = false)
    private RetentionDataCategory dataCategory;

    /** UUID użytkownika, który wywołał purge ręcznie. NULL dla {@code trigger_type=AUTO}. */
    @Column(name = "triggered_by")
    private UUID triggeredBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 10, updatable = false)
    private PurgeTriggerType triggerType;

    /** Granica czasowa purge — wiersze starsze niż ta data zostały (są) usuwane. */
    @Column(name = "cutoff_date", nullable = false, updatable = false)
    private LocalDate cutoffDate;

    /** Suma usuniętych wierszy ze wszystkich batchy i tabel danej kategorii. NULL dopóki RUNNING. */
    @Column(name = "rows_deleted")
    private Long rowsDeleted;

    /** {@code RUNNING} → {@code COMPLETED} lub {@code FAILED}. */
    @Column(name = "status", nullable = false, length = 15)
    private String status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Komunikat błędu przy {@code status=FAILED}. NULL przy sukcesie. */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** Status {@code RUNNING} — operacja w toku, jeszcze niezakończona. */
    public static final String STATUS_RUNNING = "RUNNING";

    /** Status {@code COMPLETED} — operacja zakończona sukcesem. */
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** Status {@code FAILED} — operacja przerwana błędem. */
    public static final String STATUS_FAILED = "FAILED";
}
