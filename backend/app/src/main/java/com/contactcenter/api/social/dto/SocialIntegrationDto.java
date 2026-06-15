package com.contactcenter.api.social.dto;

import com.contactcenter.domain.social.SocialPlatform;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO odpowiedzi dla integracji social media.
 *
 * <p>Nigdy nie zawiera accessToken w plaintext – tylko metadane integracji.
 *
 * @param integrationId  unikalny identyfikator integracji
 * @param platform       platforma (FACEBOOK, INSTAGRAM, WHATSAPP)
 * @param pageId         ID strony/konta na platformie
 * @param displayName    nazwa wyświetlana (czytelna dla supervisora)
 * @param webhookStatus  aktualny status: ACTIVE, INACTIVE, ERROR, EXPIRED_TOKEN
 * @param tokenExpiresAt czas wygaśnięcia tokenu (null = brak wygaśnięcia, np. WhatsApp)
 * @param webhookUrl     URL webhooka skonfigurowany na platformie
 * @param createdAt      czas utworzenia integracji
 * @param updatedAt      czas ostatniej modyfikacji
 */
public record SocialIntegrationDto(
        UUID integrationId,
        SocialPlatform platform,
        String pageId,
        String displayName,
        String webhookStatus,
        Instant tokenExpiresAt,
        String webhookUrl,
        Instant createdAt,
        Instant updatedAt
) {
}
