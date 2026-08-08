package com.contactcenter.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Odpowiedź endpointu {@code GET /api/admin/metrics/contacts-by-channel}.
 *
 * <p>Macierz liczby kontaktów w wybranym zakresie dat ({@code fromDate}-{@code toDate}, oba
 * krańce włącznie, sterowanym parametrem {@code days} = 7/30/90) w podziale na tenanta i kanał
 * komunikacji (jeden z 5 stałych kanałów: PHONE, EMAIL, SOCIAL_FACEBOOK, SOCIAL_INSTAGRAM,
 * SOCIAL_WHATSAPP).
 *
 * <p>Definicja "kontaktu" jest IDENTYCZNA jak w {@link TenantMetrics#contactsToday()} /
 * {@link UsageMetrics#contactsHandledToday()} (status COMPLETED/TRANSFERRED, {@code duration_seconds}
 * niepuste) – dla zakresu zawężonego do dnia dzisiejszego ({@code days} nieużyte / zakres = dziś)
 * suma {@link TenantChannelRow#total()} dla danego tenanta zgadza się z odpowiadającym
 * {@code TenantMetrics.contactsToday}.
 *
 * <p>Dane są cachowane w Redis (TTL 5 min, {@code RedisConfig.CacheNames.ADMIN_METRICS_CHANNEL_BREAKDOWN},
 * klucz zależny od {@code days}).
 */
@Schema(description = "Macierz liczby kontaktów w wybranym zakresie dat w podziale na tenanta i kanał komunikacji")
public record ContactChannelMatrix(

        @Schema(description = "Kanoniczna, stała lista kanałów (kolumny macierzy) – zawsze te "
                + "same 5 wartości w tej samej kolejności",
                example = "[\"PHONE\",\"EMAIL\",\"SOCIAL_FACEBOOK\",\"SOCIAL_INSTAGRAM\",\"SOCIAL_WHATSAPP\"]")
        List<String> channels,

        @Schema(description = "Jeden wiersz per tenant – wszyscy tenanci platformy, niezależnie "
                + "od statusu (spójnie z resztą dashboardu), nawet z samymi zerami")
        List<TenantChannelRow> tenants,

        @Schema(description = "Pierwszy dzień zakresu (włącznie), np. \"dziś minus 6 dni\" dla days=7",
                example = "2026-07-09")
        LocalDate fromDate,

        @Schema(description = "Ostatni dzień zakresu (włącznie) – zawsze dzień dzisiejszy",
                example = "2026-07-15")
        LocalDate toDate,

        @Schema(description = "Czas wygenerowania odpowiedzi (UTC)", example = "2026-07-15T10:00:00Z")
        Instant generatedAt

) {
}
