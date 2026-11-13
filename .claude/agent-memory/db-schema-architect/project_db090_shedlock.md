---
name: project-db090-shedlock
description: DB-090/V149 (2026-11-13) tabela shedlock, TIMESTAMP(3) bez strefy, ShedLock 6.10.0, baza dev = contact_center (user ccapp)
metadata:
  type: project
---

V149 `shedlock` (platformowa, bez tenant_id/RLS). TIMESTAMP(3) a nie TIMESTAMPTZ: provider usingDbTime() na Postgres uzywa `timezone('utc', CURRENT_TIMESTAMP)` (naiwny UTC) - timestamptz przesunalby wartosci o strefe sesji. Maven: net.javacrumbs.shedlock:shedlock-provider-jdbc-template (+ shedlock-spring) 6.10.0 (skompilowany pod Spring 6.2.10, Boot projektu 3.3.5 = Spring 6.1 - BE-171 ma zweryfikowac).
RlsValidationService klasyfikuje po kolumnie tenant_id NOT NULL, wiec tabela bez tenant_id jest poza walidacja bez zmian w kodzie. Rola `cc_app` nie istnieje (role: app_user, admin_user, ccapp); app_user dostaje DML tez z ALTER DEFAULT PRIVILEGES (V012).
Zywa baza dev: `docker exec cc-postgres psql -U ccapp -d contact_center` (nie postgres/contact_center_dev). Nie zrzucaj env kontenera (klasyfikator odmawia).
