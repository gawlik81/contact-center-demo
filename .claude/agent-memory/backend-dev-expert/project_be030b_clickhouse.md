---
name: BE-030b ClickHouse Data Warehouse
description: ClickHouseDwWriter jako primary bean gdy etl.dw.type=clickhouse; port 9002 TCP bo MinIO zajmuje 9000
type: project
---

ClickHouse 24.3 podpięty jako DW dla ETL pipeline zamiast PostgreSQL fallback.

**Why:** PostgresDwWriter był fallbackiem; BE-030b podłącza prawdziwy ClickHouse dla analityki.

**How to apply:** Gdy modyfikujesz ETL lub DW – pamiętaj:

- Port ClickHouse TCP to `9002` na hoście (MinIO używa `9000`); HTTP interface `8123` bez zmian
- `ClickHouseDwWriter` – `@Primary` + `@ConditionalOnProperty(name="etl.dw.type", havingValue="clickhouse")`
- `PostgresDwWriter` – `@ConditionalOnProperty(name="etl.dw.type", havingValue="postgres", matchIfMissing=true)`
- `ClickHouseDataSourceConfig` tworzy `DriverManagerDataSource` (bez Hikari) dla beana `clickhouseDataSource`
- `ClickHouseDwWriter` NIE ma `@Transactional` – ClickHouse nie wspiera transakcji ACID
- INSERT zamiast `ON CONFLICT` – deduplikacja przez `ReplacingMergeTree(updated_at)`
- `LowCardinality(String)` w ClickHouse nie przechowuje null – wstawiaj pusty String `""`
- `queue_id` / `campaign_id` – null mapuj na `"00000000-0000-0000-0000-000000000000"` (DEFAULT w tabeli)
- Schemat inicjalizowany przez `clickhouse-init` w docker-compose (exec `V001__create_contacts_dw.sql`)
- `application-dev.yml`: `etl.dw.type: clickhouse`, `spring.datasource.clickhouse.url`
