package com.contactcenter.domain.retention;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Horyzont retencji (w miesiącach) dla tabele PLATFORMOWE (bez kategorii {@link RetentionDataCategory},
 * bez polityki per tenant w {@code tenant_retention_policy}) — {@code audit_log} i
 * {@code plugin_invocation_log} (BE-123, EPIC-30, D5).
 *
 * <p><strong>D5 (ZAŁOŻENIE projektowe DESIGN EPIC-29/30, NIE formalnie potwierdzone prawnie):</strong>
 * 24 miesiące dla obu tabel. Wartość ta jest już dziś zakładana przez istniejące funkcje SQL
 * {@code drop_old_audit_log_partitions}/{@code drop_old_plugin_invocation_log_partitions}
 * ({@code p_retention_months INT DEFAULT 24}, V004/V077) — nic do tego ticketu nie zostało
 * "wymyślone na nowo"; ten komponent jedynie podaje tę samą wartość domyślną {@link PartitionReclaimJob}
 * (Java), żeby {@code DROP TABLE} (Poziom 2) i funkcje SQL (backstop, dziś bez wołającego — patrz
 * notatka wykonania BE-123 w {@code TASKS-BACKEND.md}) były zgodne. Wymaga formalnego potwierdzenia
 * prawnego/właściciela produktu — do tego momentu traktować jak bezpiecznik wdrożeniowy, analogicznie
 * do {@code retention.purge.delete-messages} (BE-126) i
 * {@code email.attachments.pending-sweep-delete-enabled} (BE-131).
 *
 * <p><strong>Walidacja przy starcie:</strong> {@link #validate()} ({@code @PostConstruct}) sprawdza
 * {@code >= 1} dla obu wartości. Nieprawidłowa wartość (np. {@code 0} albo ujemna z ENV var) NIE
 * blokuje startu aplikacji (w odróżnieniu od np. {@code JwtService}, gdzie błędny klucz prywatny
 * uniemożliwia jakiekolwiek działanie serwisu) — ten komponent celowo tylko loguje WARN i wraca do
 * bezpiecznej wartości domyślnej (24), bo błąd konfiguracji horyzontu platformowego nie jest
 * krytyczny dla uruchomienia aplikacji (dotyczy wyłącznie cotygodniowego joba odzyskiwania miejsca,
 * {@link PartitionReclaimJob}) — zablokowanie startu całej aplikacji z tego powodu byłoby
 * nieproporcjonalną reakcją; zdegradowane zachowanie (fallback) jest tu bezpieczniejsze niż odmowa
 * startu.
 */
@Slf4j
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "retention.platform")
public class PlatformRetentionProperties {

    /** Wartość domyślna/fallback (D5) — patrz javadoc klasy. */
    static final int DEFAULT_MONTHS = 24;

    /** Horyzont (miesiące) dla {@code audit_log} — {@code retention.platform.audit-log-months}. */
    private int auditLogMonths = DEFAULT_MONTHS;

    /**
     * Horyzont (miesiące) dla {@code plugin_invocation_log} —
     * {@code retention.platform.plugin-invocation-log-months}.
     */
    private int pluginInvocationLogMonths = DEFAULT_MONTHS;

    @PostConstruct
    void validate() {
        if (auditLogMonths < 1) {
            log.warn("[PlatformRetentionProperties] retention.platform.audit-log-months={} jest "
                            + "nieprawidłowe (wymagane >= 1) — fallback do wartości domyślnej {}.",
                    auditLogMonths, DEFAULT_MONTHS);
            auditLogMonths = DEFAULT_MONTHS;
        }
        if (pluginInvocationLogMonths < 1) {
            log.warn("[PlatformRetentionProperties] retention.platform.plugin-invocation-log-months={} "
                            + "jest nieprawidłowe (wymagane >= 1) — fallback do wartości domyślnej {}.",
                    pluginInvocationLogMonths, DEFAULT_MONTHS);
            pluginInvocationLogMonths = DEFAULT_MONTHS;
        }
    }

    /**
     * Rozwiązuje horyzont (miesiące) na podstawie klucza właściwości logicznej —
     * patrz {@link PartitionReclaimJob.ThresholdSource.PlatformHorizon#propertyKey()}.
     *
     * @param propertyKey {@code "audit-log-months"} albo {@code "plugin-invocation-log-months"}
     * @return skonfigurowany (lub fallback) horyzont w miesiącach
     * @throws IllegalArgumentException dla nieznanego klucza — defensywny bezpiecznik, nie powinien
     *         się zdarzyć, bo klucze są zdefiniowane statycznie w {@code PartitionReclaimJob}
     */
    int monthsFor(String propertyKey) {
        return switch (propertyKey) {
            case "audit-log-months" -> auditLogMonths;
            case "plugin-invocation-log-months" -> pluginInvocationLogMonths;
            default -> throw new IllegalArgumentException(
                    "Nieznany klucz horyzontu platformowego: " + propertyKey);
        };
    }
}
