package com.contactcenter.api.queue.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Żądanie PUT /api/queues/{queueId}/assignment (BE-046).
 *
 * <p>Gdy {@code allAgents=true}, pola {@code directAgentIds} i {@code groupIds}
 * są ignorowane przez serwis (flaga przesłania jawnych przypisań).
 *
 * @param allAgents      (wymagane) true = wszyscy agenci tenanta, false = tylko jawnie przypisani
 * @param directAgentIds UUID agentów do bezpośredniego przypisania; null traktowane jako pusta lista
 * @param groupIds       UUID grup do przypisania; null traktowane jako pusta lista
 */
public record UpdateQueueAssignmentRequest(
        @NotNull Boolean allAgents,
        List<UUID> directAgentIds,
        List<UUID> groupIds
) {
}
