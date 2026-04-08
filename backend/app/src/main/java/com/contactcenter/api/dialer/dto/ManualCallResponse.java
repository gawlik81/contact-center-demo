package com.contactcenter.api.dialer.dto;

import java.util.UUID;

/**
 * Odpowiedź z endpointu {@code POST /api/dialer/manual/call}.
 *
 * @param callId    identyfikator sesji telefonicznej (SID z providera lub UUID lokalny)
 * @param recordId  UUID rekordu {@code campaign_contact} – teraz w statusie DIALING
 * @param campaignId UUID kampanii
 * @param agentId   UUID agenta obsługującego połączenie
 * @param phone     numer telefonu klienta (zamaskowany w logach, pełny w odpowiedzi)
 */
public record ManualCallResponse(
        String callId,
        UUID recordId,
        UUID campaignId,
        UUID agentId,
        String phone
) {}
