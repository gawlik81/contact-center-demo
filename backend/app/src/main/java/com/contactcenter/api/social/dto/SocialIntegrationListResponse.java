package com.contactcenter.api.social.dto;

import java.util.List;

/**
 * Odpowiedź listy integracji social media dla tenanta.
 *
 * @param integrations lista integracji; nigdy null (pusta lista gdy brak integracji)
 * @param total        liczba integracji
 */
public record SocialIntegrationListResponse(
        List<SocialIntegrationDto> integrations,
        int total
) {
    public static SocialIntegrationListResponse of(List<SocialIntegrationDto> integrations) {
        return new SocialIntegrationListResponse(integrations, integrations.size());
    }
}
