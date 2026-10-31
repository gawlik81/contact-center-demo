package com.contactcenter.api.contact.dto;

import java.time.Instant;

/**
 * Response element of {@code GET /api/agent/me/active-contacts} (BE-157): everything the agent desktop
 * needs to rebuild a contact tab after a page reload, without extra calls.
 *
 * @param contactId          contact UUID as String
 * @param channel            agent desktop type: PHONE, EMAIL, CHAT or SOCIAL (SOCIAL_* family collapsed by
 *                           {@code ContactChannels}, same as {@code assigned-contact})
 * @param direction          INBOUND / OUTBOUND
 * @param status             raw database status: ASSIGNED, ACTIVE, ON_HOLD or COMPLETED
 * @param phase              derived: RINGING (ASSIGNED), IN_PROGRESS (ACTIVE, ON_HOLD), WRAP_UP (ACW)
 * @param assignedAt         when routing assigned the contact (nullable)
 * @param startedAt          contact start (nullable)
 * @param endedAt            contact end (nullable)
 * @param wrapUpStartedAt    equals {@code endedAt} for WRAP_UP, otherwise null (explicit so that the frontend
 *                           does not guess the semantics of {@code endedAt})
 * @param customerId         customer UUID as String (nullable)
 * @param customerIdentifier phone number / address of the remote party ({@code remote_address})
 * @param customerName       real customer name when resolvable, otherwise {@code customerIdentifier}
 * @param queueId            queue UUID as String (nullable)
 * @param queueName          queue name ("" when unknown)
 * @param subject            e-mail subject from {@code channel_metadata.subject}, otherwise null
 */
public record ActiveContactResponse(
        String contactId,
        String channel,
        String direction,
        String status,
        ContactPhase phase,
        Instant assignedAt,
        Instant startedAt,
        Instant endedAt,
        Instant wrapUpStartedAt,
        String customerId,
        String customerIdentifier,
        String customerName,
        String queueId,
        String queueName,
        String subject
) {
}
