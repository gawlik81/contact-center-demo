package com.contactcenter.domain.plugin.dto;

import com.contactcenter.domain.plugin.PluginInvocationLog;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO jednego wpisu dziennika wywołań pluginu, zwracany przez
 * {@code GET /api/supervisor/plugins/{installationId}/invocations} (BE-105).
 *
 * @param id                       identyfikator wpisu
 * @param invokedAt                czas wywołania
 * @param tenantPluginInstallationId instalacja, której wynik dotyczy
 * @param extensionPoint           punkt rozszerzenia (np. {@code PRE_CONTACT_CONNECT})
 * @param relatedContactId          kontakt powiązany z wywołaniem, lub {@code null}
 * @param status                    wynik próby (SUCCESS/FAILED/TIMED_OUT/CIRCUIT_OPEN/SKIPPED_DISABLED)
 * @param durationMs                czas trwania wywołania w milisekundach, lub {@code null}
 * @param errorSummary              skrót błędu, lub {@code null}
 * @param requestPayloadRedacted     snapshot payloadu żądania z usuniętym PII (JSON string), lub {@code null}
 */
public record PluginInvocationLogDto(
        UUID id,
        Instant invokedAt,
        UUID tenantPluginInstallationId,
        String extensionPoint,
        UUID relatedContactId,
        String status,
        Integer durationMs,
        String errorSummary,
        String requestPayloadRedacted) {

    public static PluginInvocationLogDto from(PluginInvocationLog entity) {
        return new PluginInvocationLogDto(
                entity.getId(),
                entity.getInvokedAt(),
                entity.getTenantPluginInstallationId(),
                entity.getExtensionPoint(),
                entity.getRelatedContactId(),
                entity.getStatus(),
                entity.getDurationMs(),
                entity.getErrorSummary(),
                entity.getRequestPayloadRedacted());
    }
}
