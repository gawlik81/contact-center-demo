package com.contactcenter.api.telephony.dto;

import com.contactcenter.domain.telephony.TelephonyAdapter;
import com.contactcenter.domain.telephony.TransferTargetType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Żądanie przekazania aktywnego połączenia.
 *
 * <p>Walidacja kombinacji pól:
 * <ul>
 *   <li>{@code targetType=PHONE} wymaga {@code phoneNumber}</li>
 *   <li>{@code targetType=AGENT} wymaga {@code agentId}</li>
 *   <li>{@code targetType=QUEUE} wymaga {@code queueId} i wymusza {@code transferType=BLIND}</li>
 * </ul>
 *
 * <p>Sprawdzana przez {@link com.contactcenter.domain.telephony.TransferRequest#validate()}
 * wywołane po mapowaniu DTO → domenowy obiekt w serwisie.
 */
public record TransferCallRequest(
        @NotNull(message = "transferType is required")
        TelephonyAdapter.TransferType transferType,

        @NotNull(message = "targetType is required")
        TransferTargetType targetType,

        /** Numer telefonu E.164 – wymagany gdy targetType=PHONE. */
        String phoneNumber,

        /** UUID agenta docelowego – wymagany gdy targetType=AGENT. */
        UUID agentId,

        /** UUID kolejki docelowej – wymagany gdy targetType=QUEUE. */
        UUID queueId
) {}
