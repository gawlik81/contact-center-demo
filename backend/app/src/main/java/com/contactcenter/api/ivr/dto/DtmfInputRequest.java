package com.contactcenter.api.ivr.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Żądanie symulacji wejścia DTMF (dev endpoint).
 *
 * <p>Używany przez {@code POST /api/ivr/dtmf} do testowania przepływu IVR
 * bez prawdziwego połączenia telefonicznego.
 *
 * @param callId identyfikator sesji połączenia (musi istnieć aktywna sesja IVR)
 * @param key    klawisz DTMF ("0"–"9", "*", "#", "timeout", "no-input")
 */
public record DtmfInputRequest(
        @NotBlank(message = "callId jest wymagany")
        String callId,

        @NotBlank(message = "key jest wymagany")
        String key
) {
}
