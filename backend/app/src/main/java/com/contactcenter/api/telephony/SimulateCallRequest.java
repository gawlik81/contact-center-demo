package com.contactcenter.api.telephony;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * DTO dla endpointu symulacji zdarzeń telefonicznych (tylko profil dev).
 */
public record SimulateCallRequest(

        /**
         * Typ akcji do zasymulowania.
         * Dozwolone: INCOMING, ANSWER, HANGUP, HOLD, UNHOLD, BLIND_TRANSFER, ATTENDED_TRANSFER, BRIDGE.
         */
        @NotBlank(message = "action jest wymagana")
        String action,

        /**
         * Identyfikator tenanta dla symulacji.
         */
        @NotNull(message = "tenantId jest wymagany")
        UUID tenantId,

        /**
         * Opcjonalny callId – wymagany dla akcji innych niż INCOMING.
         */
        String callId,

        /**
         * Numer dzwoniącego (wymagany dla INCOMING).
         */
        @Pattern(regexp = "^\\+?[0-9\\-\\s]{7,20}$", message = "Nieprawidłowy format numeru")
        String from,

        /**
         * Numer docelowy (wymagany dla INCOMING i TRANSFER).
         */
        @Pattern(regexp = "^\\+?[0-9\\-\\s]{7,20}$", message = "Nieprawidłowy format numeru")
        String to,

        /**
         * Agent powiązany z symulacją (opcjonalny).
         */
        UUID agentId,

        /**
         * Drugi callId – wymagany dla BRIDGE (łączy dwie nogi attended transfer).
         */
        String secondCallId
) {}
