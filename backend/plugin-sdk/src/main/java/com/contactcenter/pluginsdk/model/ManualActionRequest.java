package com.contactcenter.pluginsdk.model;

import java.util.Map;
import java.util.UUID;

/**
 * Request payload delivered to a plugin for the {@code MANUAL_ACTION} extension point, fired
 * when an agent explicitly invokes a plugin-provided action from the desktop UI (e.g. clicking
 * a toolbar button declared in the manifest's {@code manualActions}).
 *
 * <p>{@code MANUAL_ACTION} is <b>blocking</b> — the agent is waiting on a direct
 * request/response — but still timeout-bounded, like {@code PRE_CONTACT_CONNECT}.
 *
 * @param actionId   identifier of the action being invoked, matching one of the
 *                    {@code manualActions[].actionId} values declared in the plugin manifest
 * @param contactId  identifier of the contact the action was triggered from, or {@code null}
 *                    if triggered outside the context of a specific contact
 * @param customerId identifier of the related customer, or {@code null} if unknown
 * @param parameters  additional action-specific parameters supplied by the agent desktop UI,
 *                    never {@code null} (may be empty)
 */
public record ManualActionRequest(
        String actionId,
        UUID contactId,
        UUID customerId,
        Map<String, Object> parameters) {
}
