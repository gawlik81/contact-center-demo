package com.contactcenter.domain.etl;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO reprezentujący jeden wiersz w Data Warehouse dla rekordu kampanii.
 *
 * <p>Mapuje rekord z tabeli {@code campaign_contact} (złączonej z {@code campaign})
 * do tabeli analitycznej {@code campaigns_dw} w ClickHouse.
 *
 * <p>Zasilanie odbywa się przez CDC (polling po {@code COALESCE(cc.updated_at, cc.created_at)})
 * z kluczem w {@code etl_sync_state} pod nazwą {@code "campaign_contact"}.
 *
 * @param campaignId    UUID kampanii
 * @param tenantId      UUID tenanta
 * @param recordId      UUID rekordu listy kontaktów kampanii
 * @param status        status rekordu (PENDING, DIALING, CONNECTED, NO_ANSWER, FAILED, COMPLETED, SKIPPED)
 * @param dispositionCode wynik zarejestrowany przez agenta (nullable)
 * @param attemptCount  liczba wykonanych prób połączenia
 * @param lastAttemptAt czas ostatniej próby (nullable)
 * @param campaignType  typ kampanii z tabeli campaign (OUTBOUND_VOICE, OUTBOUND_EMAIL)
 * @param dialerType    typ dialera z tabeli campaign (PROGRESSIVE, PREDICTIVE, MANUAL)
 */
public record CampaignDwRow(
        UUID campaignId,
        UUID tenantId,
        UUID recordId,
        String status,
        String dispositionCode,
        int attemptCount,
        Instant lastAttemptAt,
        String campaignType,
        String dialerType
) {}
