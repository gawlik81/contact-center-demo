package com.contactcenter.api.plugin.dto;

import java.util.Map;
import java.util.UUID;

/**
 * Body żądania {@code POST /api/agent/plugins/{installationId}/manual-action/{actionId}}
 * (EPIC-28, BE-103).
 *
 * @param contactId  identyfikator kontaktu, z kontekstu którego agent wywołał akcję, lub
 *                   {@code null} jeśli akcja nie jest powiązana z konkretnym kontaktem
 * @param customerId identyfikator klienta powiązanego z kontaktem, lub {@code null} jeśli nieznany
 * @param payload    parametry akcji specyficzne dla pluginu (przekazywane bez modyfikacji do
 *                   {@code PluginEntryPoint#onManualAction}); może być {@code null} (mapowane na
 *                   pustą mapę)
 */
public record ManualActionRequestDto(
        UUID contactId,
        UUID customerId,
        Map<String, Object> payload) {
}
