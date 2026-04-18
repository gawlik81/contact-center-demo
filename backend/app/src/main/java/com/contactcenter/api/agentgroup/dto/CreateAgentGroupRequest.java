package com.contactcenter.api.agentgroup.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DTO żądania tworzenia grupy agentów (BE-044).
 *
 * @param name unikalna nazwa grupy w obrębie tenanta (max 255 znaków)
 */
public record CreateAgentGroupRequest(
        @NotBlank(message = "Nazwa grupy nie może być pusta")
        @Size(max = 255, message = "Nazwa grupy nie może przekraczać 255 znaków")
        String name
) {
}
