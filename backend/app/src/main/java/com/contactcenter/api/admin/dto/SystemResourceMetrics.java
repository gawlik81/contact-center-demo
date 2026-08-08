package com.contactcenter.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Odpowiedź endpointu {@code GET /api/admin/metrics/resources}.
 *
 * <p>Metryki zasobów WSPÓŁDZIELONEJ instancji JVM/procesu backendu (nie per tenant –
 * CPU/RAM/wątki/pula połączeń DB/kolejki RabbitMQ są zasobem procesu, nie tenanta).
 * Pobierane bezpośrednio z {@code io.micrometer.core.instrument.MeterRegistry} (Actuator),
 * ping Redis/RabbitMQ i {@code RabbitAdmin} (głębokość kolejek).
 *
 * <p><strong>Dane NIE są cachowane</strong> – to informacja o bieżącej kondycji procesu (live),
 * cache'owanie zafałszowałoby obraz w momencie incydentu.
 */
@Schema(description = "Metryki zasobów systemowych współdzielonej instancji backendu (live, bez cache)")
public record SystemResourceMetrics(

        @Schema(description = "Zużycie CPU procesu w procentach (0-100). -1/0 gdy metryka niedostępna",
                example = "34.5")
        double cpuUsagePercent,

        @Schema(description = "Zużyta pamięć sterty JVM (bajty)", example = "536870912")
        long heapUsedBytes,

        @Schema(description = "Maksymalna dostępna pamięć sterty JVM (bajty)", example = "2147483648")
        long heapMaxBytes,

        @Schema(description = "Liczba aktywnych (żywych) wątków JVM", example = "142")
        int threadsLive,

        @Schema(description = "Czas działania procesu (sekundy)", example = "86400")
        long uptimeSeconds,

        @Schema(description = "Liczba aktywnych połączeń w puli HikariCP", example = "6")
        int dbPoolActive,

        @Schema(description = "Maksymalny rozmiar puli połączeń HikariCP", example = "20")
        int dbPoolMax,

        @Schema(description = "Status połączenia z Redis: UP lub DOWN", example = "UP")
        String redisStatus,

        @Schema(description = "Liczba kluczy sesji agentów w Redis (session:agent:*) – SUROWA liczba "
                + "kluczy, bez filtra tenanta/statusu. To metryka infrastrukturalna (rozmiar/obciążenie "
                + "Redis), CELOWO inna niż biznesowe 'agentsOnline' (suma TenantMetrics.agentsOnline w "
                + "GET /api/admin/metrics) – sesja agenta NIE jest usuwana przy wylogowaniu, tylko "
                + "aktualizowana in-place na status OFFLINE i żyje do ~8h TTL, więc ta wartość może "
                + "być wyższa niż liczba faktycznie zalogowanych agentów.", example = "23")
        int redisAgentSessions,

        @Schema(description = "Status połączenia z RabbitMQ: UP lub DOWN", example = "UP")
        String rabbitStatus,

        @Schema(description = "Głębokość (liczba wiadomości) kluczowych kolejek operacyjnych RabbitMQ")
        List<QueueDepth> queueDepths,

        @Schema(description = "Czas wygenerowania odpowiedzi (UTC)", example = "2026-07-14T10:00:00Z")
        Instant generatedAt

) {
}
