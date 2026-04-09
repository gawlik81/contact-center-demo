package com.contactcenter.domain.etl;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO reprezentujący jeden wiersz w Data Warehouse dla kontaktu.
 *
 * <p>Zawiera pola analityczne (bez PII klienta – customer_id pomijane celowo,
 * dane RODO anonimizowane już po stronie źródłowej).
 *
 * <p>Odpowiada schematowi tabeli {@code contacts_dw} (V036) i
 * tabeli ClickHouse {@code contacts_dw} w data warehouse.
 *
 * @param contactId       UUID kontaktu (klucz upsert w DW)
 * @param tenantId        UUID tenanta
 * @param agentId         UUID agenta (nullable)
 * @param queueId         UUID kolejki (nullable)
 * @param campaignId      UUID kampanii outbound (nullable)
 * @param channel         kanał: PHONE, EMAIL itp.
 * @param direction       kierunek: INBOUND, OUTBOUND
 * @param status          status: COMPLETED, ABANDONED itp.
 * @param dispositionCode wynik kontaktu (nullable)
 * @param durationSec     czas trwania w sekundach (nullable)
 * @param startedAt       czas rozpoczęcia
 * @param endedAt         czas zakończenia (nullable)
 * @param queuedAt        czas trafienia do kolejki
 * @param remoteAddress   numer CLI / email nadawcy (nullable)
 */
public record ContactDwRow(
        UUID contactId,
        UUID tenantId,
        UUID agentId,
        UUID queueId,
        UUID campaignId,
        String channel,
        String direction,
        String status,
        String dispositionCode,
        Integer durationSec,
        Instant startedAt,
        Instant endedAt,
        Instant queuedAt,
        String remoteAddress
) {}
