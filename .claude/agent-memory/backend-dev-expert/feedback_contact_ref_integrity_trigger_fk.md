---
name: feedback-contact-ref-integrity-trigger-fk
description: Tabela contact nie ma FK (partycjonowana) – trigger fn_contact_ref_integrity (V016) wymaga REALNYCH wierszy app_user/queue/campaign w tym samym tenancie przy INSERT/UPDATE agent_id/queue_id/campaign_id
metadata:
  type: feedback
---

`contact` jest `PARTITION BY RANGE (started_at)` – PostgreSQL 16 nie wspiera FK na partycjonowanej
tabeli, więc integralność referencyjna `customer_id`/`agent_id`/`queue_id`/`campaign_id` jest
wymuszana przez trigger `fn_contact_ref_integrity()` (V016), nie przez FK. Trigger `RAISE EXCEPTION`
gdy wartość nie jest NULL i nie istnieje odpowiadający wiersz w `customer`/`app_user`/`queue`/
`campaign` o tym samym `tenant_id` (dla `customer`/`app_user` dodatkowo `is_deleted = FALSE`).

**Why:** Testy integracyjne na realnej bazie (`PostgresTestDatabase`), które wstawiają wiersz
`contact` z losowym `UUID.randomUUID()` jako `agent_id`/`queue_id`/`campaign_id` (wzorzec wygodny przy
mockach, gdzie FK nie istnieje), dostają `PSQLException: ... nie istnieje lub nie nalezy do tenant ...`
mimo że w schemacie `contact` nie widać żadnego FK na te kolumny — błąd pojawia się tylko na
prawdziwym Postgresie z pełnym Flyway, nie w testach mockowych (BE-141, `EtlSyncServiceImplIntegrationTest`).

**How to apply:** Przy insertach testowych do `contact` z niepustym `agent_id`: wstaw najpierw
`INSERT INTO app_user (user_id, tenant_id, role, email, password_hash) VALUES (?, ?, 'AGENT', ?, 'x')`;
z niepustym `queue_id`: `INSERT INTO queue (queue_id, tenant_id, name) VALUES (?, ?, '...')`;
z niepustym `campaign_id`: `INSERT INTO campaign (campaign_id, tenant_id, name) VALUES (?, ?, '...')`
(wzorzec z `ContactRefIntegrityNarrowingTest`). `customer_id` wymaga wiersza w `customer` z
`is_deleted = FALSE`. Jeśli te kolumny pozostają `NULL` w teście, trigger nic nie sprawdza – można
pominąć seed. `tenant_id` samego kontaktu NIE jest walidowany względem tabeli `tenant` (brak FK i brak
takiego sprawdzenia w triggerze) – losowy UUID jest bezpieczny.
Powiązane: [[feedback-jpa-real-db-integration-test-harness]].
