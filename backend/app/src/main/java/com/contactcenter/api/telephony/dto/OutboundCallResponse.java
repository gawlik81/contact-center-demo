package com.contactcenter.api.telephony.dto;

import java.util.UUID;

/**
 * DTO odpowiedzi po inicjowaniu wychodzącego połączenia ad hoc.
 *
 * @param contactId UUID rekordu kontaktu utworzonego dla tego połączenia
 * @param callId    identyfikator sesji połączenia (Twilio CallSid lub mock UUID)
 */
public record OutboundCallResponse(
        UUID contactId,
        String callId
) {}
