-- =============================================================================
-- DB-066 (EPIC-30): pomiar wolumenu email_message - bramka partycjonowania D2
-- TYLKO ODCZYT. Skrypt nie zmienia stanu bazy.
--
-- Uruchomienie (wejscie przez stdin, bez hasla - wewnatrz kontenera):
--   docker exec -i cc-postgres psql -U ccapp -d contact_center -X -v ON_ERROR_STOP=1 \
--     < scripts/epic-30/db-066-email-message-volume.sql
--
-- Srodowisko docelowe (stage/prod-like) - ten sam skrypt, wynik oznaczyc jako
-- "stage"/"prod" zamiast "demo". Wynik jest deterministyczny przy niezmienionych danych.
--
-- WAZNE (RLS): skrypt MUSI byc uruchomiony przez rola z BYPASSRLS lub superuser
-- (w demo: ccapp). Rola aplikacyjna app_user bez ustawionego GUC
-- app.current_tenant_id widzi 0 wierszy BEZ BLEDU - wynik bylby cicho falszywy.
-- Sprawdzenie przed uruchomieniem: SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user;
-- =============================================================================

-- Zabezpieczenie: kazda proba zapisu zakonczy sie bledem.
SET default_transaction_read_only = on;
-- Miesiace i daty liczone w UTC (baza dziala w UTC; hosty moga miec inna strefe).
SET TIME ZONE 'UTC';

-- -----------------------------------------------------------------------------
-- 1. Rozmiar fizyczny i liczba wierszy
-- -----------------------------------------------------------------------------
\echo '== 1. Rozmiar fizyczny (bajty) =='
SELECT
    pg_total_relation_size('email_message')                                   AS total_bytes,
    pg_relation_size('email_message')                                         AS heap_main_bytes,
    pg_total_relation_size(c.reltoastrelid)                                   AS toast_total_bytes,
    pg_indexes_size('email_message')                                          AS indexes_bytes,
    pg_size_pretty(pg_total_relation_size('email_message'))                   AS total_pretty,
    c.relpages                                                                AS relpages,
    c.reltuples                                                               AS reltuples_planner_estimate
FROM pg_class c
WHERE c.oid = 'email_message'::regclass;

\echo '== 1b. Liczba wierszy (count(*) - wartosc wiarygodna; reltuples moze byc przestarzale) =='
SELECT
    count(*)                                                AS rows_total,
    count(*) FILTER (WHERE direction = 'INBOUND')           AS rows_inbound,
    count(*) FILTER (WHERE direction = 'OUTBOUND')          AS rows_outbound,
    count(DISTINCT tenant_id)                               AS tenants_with_email,
    (SELECT count(*) FROM tenant)                           AS tenants_total
FROM email_message;

-- -----------------------------------------------------------------------------
-- 2. Rozmiar wiersza (pg_column_size) - srednia, mediana, p95, max
--    Uwaga: pg_column_size(t.*) odzwierciedla reprezentacje zapisana w heap
--    (wartosci TOAST/skompresowane jako wskazniki/rozmiar zapisany). Dlatego
--    obok podajemy nieskompresowany rozmiar tresci (octet_length).
-- -----------------------------------------------------------------------------
\echo '== 2. Rozmiar wiersza =='
SELECT
    count(*)                                                                         AS n,
    round(avg(pg_column_size(t.*)))::bigint                                          AS avg_row_stored_bytes,
    percentile_cont(0.5)  WITHIN GROUP (ORDER BY pg_column_size(t.*))::bigint        AS p50_row_stored_bytes,
    percentile_cont(0.95) WITHIN GROUP (ORDER BY pg_column_size(t.*))::bigint        AS p95_row_stored_bytes,
    max(pg_column_size(t.*))                                                         AS max_row_stored_bytes,
    round(avg(octet_length(COALESCE(t.body_html, '')) + octet_length(COALESCE(t.body_text, ''))))::bigint
                                                                                     AS avg_body_uncompressed_bytes,
    percentile_cont(0.95) WITHIN GROUP (
        ORDER BY octet_length(COALESCE(t.body_html, '')) + octet_length(COALESCE(t.body_text, ''))
    )::bigint                                                                        AS p95_body_uncompressed_bytes,
    count(*) FILTER (WHERE t.body_html IS NULL AND t.body_text IS NULL)              AS rows_without_body
FROM email_message t;

-- -----------------------------------------------------------------------------
-- 3. Rozklad miesieczny po COALESCE(received_at, sent_at, created_at) (UTC)
-- -----------------------------------------------------------------------------
\echo '== 3. Rozklad miesieczny (UTC) =='
WITH per_row AS (
    SELECT
        date_trunc('month', COALESCE(t.received_at, t.sent_at, t.created_at))  AS month_utc,
        t.direction,
        pg_column_size(t.*)                                                     AS row_bytes,
        jsonb_array_length(t.attachments)                                       AS att_count,
        COALESCE((
            SELECT sum(CASE WHEN jsonb_typeof(a->'size_bytes') = 'number'
                            THEN (a->>'size_bytes')::bigint END)
            FROM jsonb_array_elements(t.attachments) a
        ), 0)                                                                   AS att_bytes
    FROM email_message t
)
SELECT
    month_utc,
    count(*)                                                AS rows_total,
    count(*) FILTER (WHERE direction = 'INBOUND')           AS rows_inbound,
    count(*) FILTER (WHERE direction = 'OUTBOUND')          AS rows_outbound,
    sum(row_bytes)                                          AS row_stored_bytes_sum,
    round(avg(row_bytes))::bigint                           AS avg_row_stored_bytes,
    count(*) FILTER (WHERE att_count > 0)                   AS rows_with_attachments,
    sum(att_bytes)                                          AS attachments_size_bytes_sum
FROM per_row
GROUP BY month_utc
ORDER BY month_utc;

-- -----------------------------------------------------------------------------
-- 4. Zalaczniki (metadane JSONB; same pliki sa w S3, NIE w pg_total_relation_size)
--    Struktura z kodu: EmailPollingServiceImpl (INBOUND) i EmailSendServiceImpl
--    (OUTBOUND): {"filename","content_type","size_bytes","s3_key"}.
-- -----------------------------------------------------------------------------
\echo '== 4a. Udzial wiadomosci z zalacznikami =='
SELECT
    t.direction,
    count(*)                                                                         AS messages,
    count(*) FILTER (WHERE jsonb_array_length(t.attachments) > 0)                    AS messages_with_att,
    round(100.0 * count(*) FILTER (WHERE jsonb_array_length(t.attachments) > 0)
          / NULLIF(count(*), 0), 2)                                                  AS pct_with_att
FROM email_message t
GROUP BY ROLLUP (t.direction)
ORDER BY t.direction NULLS LAST;

\echo '== 4b. Zalaczniki: liczba, suma i rozmiar size_bytes, jakosc danych =='
WITH att AS (
    SELECT
        t.direction,
        a                                                                            AS item,
        CASE WHEN jsonb_typeof(a->'size_bytes') = 'number'
             THEN (a->>'size_bytes')::bigint END                                     AS size_b
    FROM email_message t
    CROSS JOIN LATERAL jsonb_array_elements(t.attachments) a
)
SELECT
    direction,
    count(*)                                                                         AS att_count,
    sum(size_b)                                                                      AS att_size_bytes_sum,
    round(avg(size_b))::bigint                                                       AS att_avg_bytes,
    percentile_cont(0.95) WITHIN GROUP (ORDER BY size_b)::bigint                     AS att_p95_bytes,
    max(size_b)                                                                      AS att_max_bytes,
    count(*) FILTER (WHERE jsonb_typeof(item->'size_bytes') IS DISTINCT FROM 'number') AS att_size_not_numeric,
    count(*) FILTER (WHERE item ? 's3_key')                                          AS att_with_s3_key,
    count(*) FILTER (WHERE item->>'s3_key' LIKE '%/pending/%')                       AS att_s3_key_pending
FROM att
GROUP BY ROLLUP (direction)
ORDER BY direction NULLS LAST;

-- -----------------------------------------------------------------------------
-- 5. Martwe krotki i autovacuum (pg_stat_user_tables)
--    Uwaga: statystyki sa przyblizone i zerowane przy restarcie / brak statystyk.
-- -----------------------------------------------------------------------------
\echo '== 5. Martwe krotki =='
SELECT
    relname,
    n_live_tup,
    n_dead_tup,
    round(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 2) AS dead_pct,
    last_vacuum,
    last_autovacuum,
    last_analyze,
    last_autoanalyze,
    autovacuum_count,
    n_tup_ins,
    n_tup_del
FROM pg_stat_user_tables
WHERE relname = 'email_message';

-- -----------------------------------------------------------------------------
-- 6. Czasy purge (retention_purge_log). Log jest na poziomie tenant x kategoria
--    (CONTACT_INTERACTIONS obejmuje takze wiadomosci), NIE na poziomie batcha
--    ani tabeli - p95 pojedynczego batcha usuwania (G4) nie jest z niego mierzalne.
-- -----------------------------------------------------------------------------
\echo '== 6a. Przebiegi purge - agregat per kategoria i status =='
SELECT
    data_category,
    status,
    count(*)                                                                         AS runs,
    sum(rows_deleted)                                                                AS rows_deleted_sum,
    round(avg(EXTRACT(EPOCH FROM (completed_at - started_at))), 3)                   AS avg_duration_s,
    round(percentile_cont(0.95) WITHIN GROUP (
              ORDER BY EXTRACT(EPOCH FROM (completed_at - started_at)))::numeric, 3) AS p95_duration_s,
    max(EXTRACT(EPOCH FROM (completed_at - started_at)))                             AS max_duration_s
FROM retention_purge_log
WHERE completed_at IS NOT NULL
GROUP BY data_category, status
ORDER BY data_category, status;

\echo '== 6b. Przebiegi purge - lista (bez tenant_id) =='
SELECT
    data_category,
    trigger_type,
    status,
    cutoff_date,
    rows_deleted,
    started_at,
    completed_at,
    round(EXTRACT(EPOCH FROM (completed_at - started_at))::numeric, 3)               AS duration_s
FROM retention_purge_log
ORDER BY started_at DESC
LIMIT 50;

-- -----------------------------------------------------------------------------
-- 7. D4 - jakosc znacznikow czasu i naglowkow dla deduplikacji
--    Fallback now() (brak INTERNALDATE po stronie IMAP) NIE jest mierzalny
--    z danych w bazie - nie zapisujemy tego przypadku osobno.
-- -----------------------------------------------------------------------------
\echo '== 7. D4: received_at / sent_at / message_id_header =='
SELECT
    count(*) FILTER (WHERE t.direction = 'INBOUND')                                  AS inbound_total,
    count(*) FILTER (WHERE t.direction = 'INBOUND'  AND t.received_at IS NULL)       AS inbound_without_received_at,
    count(*) FILTER (WHERE t.direction = 'OUTBOUND')                                 AS outbound_total,
    count(*) FILTER (WHERE t.direction = 'OUTBOUND' AND t.sent_at IS NULL)           AS outbound_without_sent_at,
    count(*) FILTER (WHERE t.received_at IS NOT NULL
                       AND t.created_at - t.received_at > interval '1 day')          AS received_before_created_gt_1d,
    count(*) FILTER (WHERE t.received_at IS NOT NULL
                       AND t.created_at - t.received_at > interval '30 days')        AS received_before_created_gt_30d,
    count(*) FILTER (WHERE t.received_at IS NOT NULL
                       AND t.received_at > t.created_at + interval '1 minute')       AS received_after_created_gt_1m,
    count(*) FILTER (WHERE t.sent_at IS NOT NULL
                       AND t.sent_at > t.created_at + interval '1 minute')           AS sent_after_created_gt_1m,
    count(*) FILTER (WHERE t.message_id_header IS NULL)                              AS message_id_header_null,
    count(*) FILTER (WHERE t.message_id_header IS NOT NULL)
      - count(DISTINCT (t.tenant_id, t.message_id_header))                           AS duplicate_header_per_tenant
FROM email_message t;

\echo '== 7b. D4: rozklad opoznienia created_at - received_at (INBOUND, sekundy) =='
SELECT
    count(*)                                                                         AS n,
    round(percentile_cont(0.5)  WITHIN GROUP (
              ORDER BY EXTRACT(EPOCH FROM (t.created_at - t.received_at)))::numeric, 1) AS p50_lag_s,
    round(percentile_cont(0.95) WITHIN GROUP (
              ORDER BY EXTRACT(EPOCH FROM (t.created_at - t.received_at)))::numeric, 1) AS p95_lag_s,
    round(max(EXTRACT(EPOCH FROM (t.created_at - t.received_at)))::numeric, 1)       AS max_lag_s
FROM email_message t
WHERE t.direction = 'INBOUND' AND t.received_at IS NOT NULL;

-- -----------------------------------------------------------------------------
-- 8. Zakres czasowy i gestosc danych (kontekst dla modelu wzrostu)
-- -----------------------------------------------------------------------------
\echo '== 8. Zakres danych =='
SELECT
    min(COALESCE(t.received_at, t.sent_at, t.created_at))                            AS min_msg_at,
    max(COALESCE(t.received_at, t.sent_at, t.created_at))                            AS max_msg_at,
    count(DISTINCT date_trunc('day', COALESCE(t.received_at, t.sent_at, t.created_at))) AS active_days,
    count(*)                                                                         AS rows_total
FROM email_message t;

-- Koniec skryptu (tylko odczyt - bez zmian stanu bazy).
