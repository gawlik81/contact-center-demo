---
name: BE-030 ETL Pipeline
description: Polling-based CDC z PostgreSQL do Data Warehouse – EtlSyncService, PostgresDwWriter, EtlStatusController
type: project
---

Implementacja polling-based CDC (bez zewnętrznego Debezium) jako @Scheduled fixedDelay=60s.

**Why:** Wymaganie rejestrowania historii kontaktów w DW z opóźnieniem < 1h, idempotentny upsert, alert przy lagu > 30 min.

**How to apply:** Przy kolejnych rozszerzeniach (np. nowe tabele) dodaj wpis do etl_sync_state i nową metodę syncTable.

## Struktura

- `domain/etl/EtlSyncService` – główna logika: readAndLockSyncState → fetchContactsForEtl → writeBatches → markDone/markError → checkLagAndAlert
- `domain/etl/DataWarehouseWriter` – interfejs (port abstrakcji DW)
- `infrastructure/etl/PostgresDwWriter` – implementacja dla PostgreSQL (fallback; ClickHouse do docelowej integracji)
- `domain/etl/ContactDwRow` – DTO record (bez PII customer_id z powodów RODO)
- `domain/etl/EtlTableStatus` – DTO record dla endpoint status
- `api/admin/EtlStatusController` – GET /api/admin/etl/status + POST /api/admin/etl/trigger

## Schemat DB

- V036: `etl_sync_state` (table_name PK, last_synced_at, status IDLE/RUNNING/DONE/ERROR) + `contacts_dw` (contact_id PK, upsert ON CONFLICT)

## Kluczowe decyzje

- Brak TenantContext w @Scheduled – cross-tenant query bez RLS, tenant_id trafia do DW w danych
- FOR UPDATE na etl_sync_state zapobiega równoległym uruchomieniom
- RODO: pomijamy kontakty gdzie customer.first_name='ANONYMIZED' (subquery NOT EXISTS)
- Pomijamy statusy robocze: QUEUED, ACTIVE, ON_HOLD
- Alert: RabbitMQ exchange=cc.events, routingKey=etl.lag.alert; błąd RabbitMQ non-critical (catch + log WARN)
- Konfiguracja: `etl.sync.fixed-delay-ms` (domyślnie 60000ms)
- Migracja V036 (backend/src/main/resources/db/migration, nie app/src)
