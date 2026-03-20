package com.contactcenter.api.contact.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Żądanie utworzenia nowego kontaktu.
 *
 * <p>Używane przez {@code POST /api/contacts}.
 * Agent inicjuje kontakt przy rozpoczęciu obsługi klienta.
 *
 * @param customerId      UUID klienta (opcjonalny – klient może być nieznany)
 * @param agentId         UUID agenta obsługującego kontakt (opcjonalny przy tworzeniu z kolejki)
 * @param queueId         UUID kolejki (opcjonalny)
 * @param campaignId      UUID kampanii outbound (opcjonalny, null dla inbound)
 * @param channel         kanał: PHONE, EMAIL, SOCIAL_FACEBOOK, SOCIAL_INSTAGRAM, SOCIAL_WHATSAPP (wymagany)
 * @param direction       kierunek: INBOUND lub OUTBOUND (wymagany)
 * @param remoteAddress   numer CLI lub email nadawcy (opcjonalny)
 * @param startedAt       czas rozpoczęcia (opcjonalny – domyślnie NOW())
 * @param channelMetadata metadane kanałowe JSONB (opcjonalny)
 */
public record CreateContactRequest(
        UUID customerId,
        UUID agentId,
        UUID queueId,
        UUID campaignId,

        @NotBlank(message = "channel jest wymagany")
        @Pattern(regexp = "PHONE|EMAIL|SOCIAL_FACEBOOK|SOCIAL_INSTAGRAM|SOCIAL_WHATSAPP",
                 message = "channel musi być jednym z: PHONE, EMAIL, SOCIAL_FACEBOOK, SOCIAL_INSTAGRAM, SOCIAL_WHATSAPP")
        String channel,

        @NotBlank(message = "direction jest wymagany")
        @Pattern(regexp = "INBOUND|OUTBOUND",
                 message = "direction musi być INBOUND lub OUTBOUND")
        String direction,

        String remoteAddress,
        Instant startedAt,
        Map<String, Object> channelMetadata
) {
}
