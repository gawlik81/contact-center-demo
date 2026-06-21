package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.ExtensionPoint;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * Wrapper SLF4J TYMCZASOWY do zapisu wyniku jednej próby wywołania pluginu — wspólny dla
 * {@link ExtensionPointPublisherImpl} (ścieżki blocking/submit-and-forget, BE-102) i
 * {@link PluginInvocationConsumer} (ścieżka fire-and-forget przez RabbitMQ, BE-104).
 *
 * <p><strong>Nie pomylić z docelowym {@code PluginInvocationLogService} (BE-105):</strong> ta
 * klasa nic nie zapisuje do {@code plugin_invocation_log} — loguje wyłącznie przez SLF4J. BE-105
 * zastąpi (lub owinie) wywołania {@link #record} realnym zapisem do bazy; wydzielenie do tej
 * jednej, małej klasy (zamiast duplikowania identycznej metody w obu miejscach wołających) ma na
 * celu, żeby ta podmiana w BE-105 dotyczyła JEDNEGO miejsca, nie dwóch kopii do ręcznego
 * scalenia.
 */
@Slf4j
final class PluginInvocationLogger {

    private PluginInvocationLogger() {
    }

    /**
     * Zapisuje wynik jednej próby wywołania pluginu.
     *
     * @param tenantId       tenant-właściciel instalacji
     * @param installationId instalacja, której wynik dotyczy
     * @param extensionPoint punkt rozszerzenia, dla którego wywołanie nastąpiło
     * @param status          wynik próby (SUCCESS/FAILED/TIMED_OUT/CIRCUIT_OPEN/SKIPPED_DISABLED)
     * @param durationMs      czas trwania wywołania w milisekundach (0 gdy nie wywołano pluginu,
     *                        np. {@code CIRCUIT_OPEN}/{@code SKIPPED_DISABLED})
     * @param errorSummary    skrót błędu, lub {@code null} gdy nie dotyczy
     */
    static void record(
            UUID tenantId, UUID installationId, ExtensionPoint extensionPoint,
            InvocationStatus status, long durationMs, String errorSummary) {
        if (status == InvocationStatus.FAILED || status == InvocationStatus.TIMED_OUT) {
            log.warn("[PluginInvocation] tenant={}, installation={}, extensionPoint={}, status={}, "
                            + "durationMs={}, error={}",
                    tenantId, installationId, extensionPoint, status, durationMs, errorSummary);
        } else if (status == InvocationStatus.SKIPPED_DISABLED || status == InvocationStatus.CIRCUIT_OPEN) {
            log.info("[PluginInvocation] tenant={}, installation={}, extensionPoint={}, status={}, "
                            + "durationMs={}, detail={}",
                    tenantId, installationId, extensionPoint, status, durationMs, errorSummary);
        } else {
            log.info("[PluginInvocation] tenant={}, installation={}, extensionPoint={}, status={}, "
                            + "durationMs={}",
                    tenantId, installationId, extensionPoint, status, durationMs);
        }
    }
}
