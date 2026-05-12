package com.contactcenter.api.telephony.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * DTO żądania inicjowania wychodzącego połączenia ad hoc przez agenta.
 *
 * @param phoneNumber numer docelowy w formacie E.164 (wymagany)
 * @param customerId  UUID klienta powiązanego z połączeniem (opcjonalny)
 */
public record OutboundCallRequest(

        @NotBlank(message = "phoneNumber jest wymagany")
        @Pattern(
                regexp = "^\\+[1-9]\\d{7,14}$",
                message = "Numer telefonu musi być w formacie E.164 (np. +48123456789)"
        )
        String phoneNumber,

        UUID customerId
) {}
