package com.contactcenter.api.telephony.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * DTO agenta dostępnego do transferu połączenia.
 *
 * <p>Zwracany przez {@code GET /api/telephony/transfer/agents}.
 * Lista jest posortowana: AVAILABLE najpierw, następnie pozostałe aktywne statusy,
 * w ramach statusu – alfabetycznie po {@code lastName}.
 */
@Schema(description = "Agent dostępny do transferu połączenia")
public record TransferAgentResponse(

        @Schema(description = "UUID agenta", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID agentId,

        @Schema(description = "Imię agenta", example = "Jan")
        String firstName,

        @Schema(description = "Nazwisko agenta", example = "Kowalski")
        String lastName,

        @Schema(description = "Aktualny status agenta", example = "AVAILABLE",
                allowableValues = {"AVAILABLE", "BUSY", "AFTER_CONTACT", "ACTIVE", "BREAK", "INACTIVE"})
        String status,

        @Schema(description = "Nazwy kolejek, do których agent jest przypisany")
        List<String> queueNames

) {}
