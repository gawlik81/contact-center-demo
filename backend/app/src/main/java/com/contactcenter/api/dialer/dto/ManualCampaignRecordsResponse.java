package com.contactcenter.api.dialer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * DTO odpowiedzi dla GET /api/dialer/manual/records.
 *
 * <p>Zawiera jedną kampanię MANUAL+RUNNING wraz z jej rekordami w statusie PENDING.
 * Endpoint zwraca listę tych obiektów – po jednym dla każdej aktywnej kampanii manualnej.
 *
 * <p>Przeznaczenie: widok agenta w panelu desktop – agent widzi dostępne kampanie
 * i może ręcznie zainicjować połączenie do wybranego rekordu (POST /api/dialer/manual/call).
 */
@Schema(description = "Kampania MANUAL z listą rekordów PENDING do ręcznego wybierania")
public record ManualCampaignRecordsResponse(

        @Schema(description = "UUID kampanii", example = "550e8400-e29b-41d4-a716-446655440000")
        UUID campaignId,

        @Schema(description = "Nazwa kampanii", example = "Kampania Q2 – sprzedaż")
        String campaignName,

        @Schema(description = "Rekordy PENDING dostępne do ręcznego wybierania")
        List<ManualCampaignRecord> records

) {

    /**
     * Pojedynczy rekord kampanii (campaign_contact) gotowy do ręcznego wybierania.
     */
    @Schema(description = "Rekord kontaktu w kampanii manualnej")
    public record ManualCampaignRecord(

            @Schema(description = "UUID rekordu campaign_contact",
                    example = "660e8400-e29b-41d4-a716-446655441111")
            UUID recordId,

            @Schema(description = "Numer telefonu", example = "+48603340006")
            String phone,

            @Schema(description = "Imię kontaktu", example = "Jan")
            String firstName,

            @Schema(description = "Nazwisko kontaktu", example = "Kowalski")
            String lastName,

            @Schema(description = "Status rekordu – zawsze PENDING w tym endpoincie", example = "PENDING")
            String status

    ) {}
}
