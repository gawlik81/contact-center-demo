package com.contactcenter.api.agentgroup.dto;

import java.util.List;
import java.util.UUID;

/**
 * DTO odpowiedzi zawierający pełną listę członków grupy agentów (BE-044).
 *
 * @param groupId   UUID grupy
 * @param groupName nazwa grupy
 * @param members   lista skrótowych danych agentów należących do grupy
 */
public record AgentGroupMembersResponse(
        UUID groupId,
        String groupName,
        List<AgentSummary> members
) {
}
