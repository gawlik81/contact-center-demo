package com.contactcenter.api.telephony;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * DTO reprezentujący zdarzenie przychodzące z zewnętrznego providera VoIP przez webhook.
 *
 * <p>Format jest uogólniony (provider-agnostic) – konkretne implementacje (Twilio, Vonage)
 * będą mapować swoje własne formaty na ten DTO w dedykowanych kontrolerach lub transformerach.
 */
public record WebhookRequest(

        /**
         * Typ zdarzenia providera. Mapowane na {@link com.contactcenter.domain.telephony.CallEvent.EventType}.
         * Dozwolone wartości: CALL_INCOMING, CALL_ANSWERED, CALL_HANGUP, CALL_TRANSFERRED.
         */
        @NotBlank(message = "eventType jest wymagany")
        String eventType,

        /**
         * Identyfikator sesji nadany przez providera.
         */
        @NotBlank(message = "callId jest wymagany")
        String callId,

        /**
         * Identyfikator tenanta – wymagany do multi-tenant routing.
         */
        @NotNull(message = "tenantId jest wymagany")
        UUID tenantId,

        /**
         * Opcjonalny identyfikator agenta powiązanego ze zdarzeniem.
         */
        UUID agentId,

        /**
         * Numer dzwoniącego (format E.164).
         */
        String from,

        /**
         * Numer docelowy (format E.164).
         */
        String to,

        /**
         * Dodatkowe metadane specyficzne dla providera lub zdarzenia.
         */
        Map<String, String> metadata
) {}
