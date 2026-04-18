package com.contactcenter.api.agentgroup.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * DTO żądania podmiany listy członków grupy agentów (BE-044).
 *
 * <p>Pusta lista {@code agentIds} jest dozwolona – usuwa wszystkich członków z grupy.
 * Każdy agentId musi być UUID agenta z rolą AGENT należącego do tego samego tenanta.
 *
 * @param agentIds nowy zestaw UUID agentów (może być pusta lista)
 */
public record ReplaceMembersRequest(
        @NotNull(message = "Lista agentów nie może być null")
        List<UUID> agentIds
) {
}
