package com.contactcenter.api.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.UUID;

/**
 * Wiersz macierzy {@link ContactChannelMatrix} – liczba kontaktów w wybranym zakresie dat
 * jednego tenanta, rozbita na kanały komunikacji.
 */
@Schema(description = "Liczba kontaktów w wybranym zakresie dat jednego tenanta w podziale na kanały komunikacji")
public record TenantChannelRow(

        @Schema(description = "UUID tenanta", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID tenantId,

        @Schema(description = "Nazwa tenanta", example = "Acme Corporation")
        String tenantName,

        @Schema(description = "Liczba kontaktów w zakresie per kanał. Klucz = nazwa kanału "
                + "(z listy ContactChannelMatrix.channels()), wartość = liczba kontaktów. "
                + "Kanał bez żadnego kontaktu ma wartość 0 – klucz NIGDY nie jest pomijany z mapy.",
                example = "{\"PHONE\":42,\"EMAIL\":10,\"SOCIAL_FACEBOOK\":0,\"SOCIAL_INSTAGRAM\":0,"
                        + "\"SOCIAL_WHATSAPP\":3}")
        Map<String, Integer> countsByChannel,

        @Schema(description = "Suma kontaktów w zakresie po wszystkich kanałach dla tego tenanta. "
                + "Dla zakresu = dzień dzisiejszy zgadza się z TenantMetrics.contactsToday dla tego "
                + "samego tenanta – to ten sam licznik, tylko rozbity na kanały.",
                example = "55")
        int total

) {
}
