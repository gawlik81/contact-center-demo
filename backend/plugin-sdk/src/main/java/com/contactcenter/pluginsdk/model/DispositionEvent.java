package com.contactcenter.pluginsdk.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Event payload delivered to a plugin for the {@code DISPOSITION_SET} extension point, fired
 * when an agent sets the outcome ("disposition") of a contact.
 *
 * <p>{@code DISPOSITION_SET} is a <b>fire-and-forget</b> extension point, published over
 * {@code cc.queue.plugin-invocation} and consumed asynchronously — plugin latency never blocks
 * the agent desktop.
 *
 * @param contactId       identifier of the contact the disposition was set on
 * @param customerId      identifier of the related customer, or {@code null} if unknown
 * @param dispositionCode the disposition code the agent selected (tenant-defined, e.g.
 *                        {@code "RESOLVED"}, {@code "CALLBACK_REQUESTED"})
 * @param agentId          identifier of the agent who set the disposition
 * @param setAt             timestamp the disposition was set
 */
public record DispositionEvent(
        UUID contactId,
        UUID customerId,
        String dispositionCode,
        UUID agentId,
        Instant setAt) {
}
