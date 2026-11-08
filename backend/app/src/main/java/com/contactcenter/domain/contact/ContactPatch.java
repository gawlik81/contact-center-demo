package com.contactcenter.domain.contact;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Set of contact columns a PATCH-style write ({@link ContactRepository#patchIfUnchanged}) should change
 * (BE-209). A {@code null} component means "leave the column as it is" - the write never touches columns
 * outside this record, so a stale entity snapshot cannot overwrite them.
 *
 * @param agentId         new owner, or {@code null} = unchanged
 * @param status          new status, or {@code null} = unchanged
 * @param assignedAt      new assignment time, or {@code null} = unchanged
 * @param endedAt         new end time, or {@code null} = unchanged
 * @param remoteAddress   new remote address, or {@code null} = unchanged
 * @param channelMetadata new channel metadata (replaces the whole JSON), or {@code null} = unchanged
 */
public record ContactPatch(
        UUID agentId,
        String status,
        Instant assignedAt,
        Instant endedAt,
        String remoteAddress,
        Map<String, Object> channelMetadata) {
}
