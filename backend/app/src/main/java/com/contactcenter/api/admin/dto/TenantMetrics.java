package com.contactcenter.api.admin.dto;

import com.contactcenter.domain.tenant.Tenant.TenantStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Metryki pojedynczego tenanta – składowa odpowiedzi {@link AdminMetricsResponse}.
 *
 * <p>Zawiera podstawowe dane operacyjne tenanta: liczbę agentów online (z Redis)
 * i łączną liczbę agentów (z bazy danych), a także dzienne wskaźniki operacyjne
 * (kontakty dzisiaj, średni czas obsługi, FCR) i sygnał capacity planningu
 * ({@code agentLimitUtilizationPercent}).
 */
@Schema(description = "Metryki operacyjne tenanta")
public record TenantMetrics(

        @Schema(description = "UUID tenanta", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID id,

        @Schema(description = "Nazwa tenanta", example = "Acme Corporation")
        String name,

        @Schema(description = "Status operacyjny tenanta", example = "ACTIVE")
        TenantStatus status,

        @Schema(description = "Liczba agentów online (status AVAILABLE, BUSY lub AFTER_CONTACT)",
                example = "12")
        int agentsOnline,

        @Schema(description = "Łączna liczba agentów tenanta (rola AGENT, is_deleted=false)",
                example = "25")
        int agentsTotal,

        @Schema(description = "Liczba kontaktów zakończonych dzisiaj (status COMPLETED/TRANSFERRED)",
                example = "134")
        int contactsToday,

        @Schema(description = "Średni czas obsługi kontaktu dzisiaj, w sekundach", example = "245")
        int avgHandleTimeSeconds,

        @Schema(description = "Procent kontaktów rozwiązanych przy pierwszym kontakcie (FCR) dzisiaj",
                example = "82.5")
        double fcrPercentage,

        @Schema(description = "Procent wykorzystania wykupionego/skonfigurowanego limitu agentów "
                + "(agentsTotal / tenant.maxAgents * 100) – sygnał do capacity planningu. "
                + "UWAGA: to INNA metryka niż agentsOnline/agentsTotal (obsada operacyjna).",
                example = "68")
        int agentLimitUtilizationPercent

) {
}
