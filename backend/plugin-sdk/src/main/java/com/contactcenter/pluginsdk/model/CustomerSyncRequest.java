package com.contactcenter.pluginsdk.model;

import java.util.UUID;

/**
 * Request payload delivered to a plugin for the {@code CUSTOMER_SYNC} extension point.
 *
 * <p>{@code CUSTOMER_SYNC} is a <b>fire-and-forget</b> extension point, published over
 * {@code cc.queue.plugin-invocation} and consumed asynchronously — plugin latency never
 * blocks the agent-facing request that triggered the sync.
 *
 * @param customerId  identifier of the customer to synchronize with the plugin's external
 *                      system (e.g. an external CRM)
 * @param reason       why the sync was triggered, e.g. {@code "CUSTOMER_CREATED"},
 *                      {@code "CUSTOMER_UPDATED"}, {@code "MANUAL_REQUEST"}
 */
public record CustomerSyncRequest(UUID customerId, String reason) {
}
