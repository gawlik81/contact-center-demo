package com.contactcenter.api.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO do ręcznego podłączenia integracji WhatsApp Business (Cloud API).
 *
 * <p>WhatsApp Business API nie używa standardowego OAuth 2.0 Code Flow – administrator
 * generuje {@code phone_number_id} oraz permanentny access token w Meta Business Suite /
 * Meta for Developers i wkleja je bezpośrednio do formularza (zamiast OAuth redirect
 * używanego dla Facebooka/Instagrama).
 *
 * <p>{@code @Size(max = 255)} na {@code phoneNumberId}/{@code displayName} odzwierciedla limit
 * kolumn {@code page_id}/{@code display_name} ({@code VARCHAR(255)}, V010__create_email_social.sql)
 * – bez tego zbyt długa wartość przechodziłaby walidację Bean Validation i kończyła się
 * nieobsłużonym {@code DataException}/500 zamiast czytelnego 422. {@code businessAccountId} nie ma
 * odrębnej kolumny (trafia do {@code platform_config} JSONB), ale ten sam limit jest rozsądnym
 * zabezpieczeniem przed nadmiernie długim wejściem.
 *
 * @param phoneNumberId     ID numeru telefonu WhatsApp Business (z Meta for Developers)
 * @param accessToken       permanentny access token (zostanie zaszyfrowany AES-256-GCM przed zapisem)
 * @param displayName       nazwa wyświetlana integracji (czytelna dla supervisora)
 * @param businessAccountId opcjonalne ID konta WhatsApp Business (WABA ID)
 */
public record WhatsAppConnectRequest(
        @NotBlank(message = "phoneNumberId nie może być pusty")
        @Size(max = 255, message = "phoneNumberId może mieć maksymalnie 255 znaków")
        String phoneNumberId,

        @NotBlank(message = "accessToken nie może być pusty")
        String accessToken,

        @NotBlank(message = "displayName nie może być pusty")
        @Size(max = 255, message = "displayName może mieć maksymalnie 255 znaków")
        String displayName,

        @Size(max = 255, message = "businessAccountId może mieć maksymalnie 255 znaków")
        String businessAccountId
) {
}
