package com.contactcenter.domain.etl;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO reprezentujący jeden wiersz w Data Warehouse dla kontaktu.
 *
 * <p>Zawiera pola analityczne (bez PII klienta – customer_id pomijane celowo,
 * dane RODO anonimizowane już po stronie źródłowej). Od BE-141 nie zawiera
 * {@code remoteAddress} (numer CLI / e-mail klienta) – kolumna nie służyła
 * analityce, a niosła PII; {@code contacts_dw.remote_address} w PostgreSQL
 * zostaje jako nullable do czasu DB-078 (drop kolumny).
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
 * @param sourceUpdatedAt wersja wiersza w źródle ({@code COALESCE(updated_at, created_at)}) - w ClickHouse
 *                        staje się kolumną wersji {@code updated_at} ({@code ReplacingMergeTree}/{@code argMax}), więc
 *                        nowsza wersja wygrywa NIEZALEŻNIE od kolejności zapisu (BE-228); {@code null} = wersja z
 *                        zegara zapisu (zachowanie sprzed BE-228)
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
        Instant sourceUpdatedAt
) {
    /** Konstruktor bez wersji źródłowej ({@code sourceUpdatedAt = null}) - zgodność wsteczna. */
    public ContactDwRow(UUID contactId, UUID tenantId, UUID agentId, UUID queueId, UUID campaignId, String channel,
                        String direction, String status, String dispositionCode, Integer durationSec,
                        Instant startedAt, Instant endedAt, Instant queuedAt) {
        this(contactId, tenantId, agentId, queueId, campaignId, channel, direction, status, dispositionCode,
                durationSec, startedAt, endedAt, queuedAt, null);
    }
}
