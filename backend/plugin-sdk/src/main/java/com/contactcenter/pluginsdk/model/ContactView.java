package com.contactcenter.pluginsdk.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable, read-only snapshot of a contact (a single interaction record: call, chat, e-mail,
 * social message, etc.), scoped to the tenant making the invocation.
 *
 * <p>This is a plain {@code record} — never a JPA entity. The host backend maps the real,
 * partitioned {@code contact} table row into this view before handing it to a plugin.
 *
 * @param contactId    unique identifier of the contact
 * @param customerId   identifier of the related customer, or {@code null} if the contact is not
 *                      (yet) linked to a known customer
 * @param channel      communication channel, e.g. {@code VOICE}, {@code EMAIL}, {@code CHAT},
 *                      {@code SOCIAL}
 * @param direction     {@code INBOUND} or {@code OUTBOUND}
 * @param status        current contact status, e.g. {@code QUEUED}, {@code ACTIVE},
 *                      {@code COMPLETED}
 * @param agentId       identifier of the agent currently or last handling the contact, or
 *                      {@code null} if unassigned
 * @param queueId       identifier of the queue the contact is or was routed through, or
 *                      {@code null}
 * @param startedAt     timestamp the contact started
 * @param endedAt       timestamp the contact ended, or {@code null} if still in progress
 */
public record ContactView(
        UUID contactId,
        UUID customerId,
        String channel,
        String direction,
        String status,
        UUID agentId,
        UUID queueId,
        Instant startedAt,
        Instant endedAt) {
}
