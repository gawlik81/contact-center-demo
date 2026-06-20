package com.contactcenter.pluginsdk.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Event payload delivered to a plugin for the {@code PRE_CONTACT_CONNECT} and
 * {@code POST_CONTACT_END} extension points.
 *
 * <p>Carries just enough information for a plugin to look up the related contact/customer
 * via {@code PluginContext} and decide what to do — it intentionally does not embed full
 * {@link ContactView}/{@link CustomerView} snapshots, so the plugin always re-fetches current
 * state through the SDK facade rather than caching a possibly stale copy.
 *
 * @param contactId   identifier of the contact this event relates to
 * @param customerId  identifier of the related customer, or {@code null} if not yet known
 *                    (e.g. an inbound call from an unrecognized number)
 * @param extensionPoint the extension point this event is being dispatched for, e.g.
 *                    {@code "PRE_CONTACT_CONNECT"} or {@code "POST_CONTACT_END"}
 * @param occurredAt  timestamp the underlying domain event occurred
 */
public record ContactEvent(
        UUID contactId,
        UUID customerId,
        String extensionPoint,
        Instant occurredAt) {
}
