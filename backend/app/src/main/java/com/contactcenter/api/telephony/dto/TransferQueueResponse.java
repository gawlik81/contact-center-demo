package com.contactcenter.api.telephony.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * DTO kolejki dostępnej jako cel transferu połączenia.
 *
 * <p>Zwracany przez {@code GET /api/telephony/transfer/queues}.
 * Lista jest posortowana alfabetycznie po {@code name}.
 *
 * <p>{@code waitingContacts} i {@code availableAgents} to snapshot z chwili wywołania –
 * mogą być nieaktualne o kilka sekund.
 */
@Schema(description = "Kolejka dostępna jako cel transferu połączenia")
public record TransferQueueResponse(

        @Schema(description = "UUID kolejki", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID queueId,

        @Schema(description = "Nazwa kolejki", example = "Obsługa klienta")
        String name,

        @Schema(description = "Liczba kontaktów oczekujących w kolejce (status QUEUED)", example = "3")
        int waitingContacts,

        @Schema(description = "Liczba agentów dostępnych w kolejce (status AVAILABLE)", example = "2")
        int availableAgents

) {}
