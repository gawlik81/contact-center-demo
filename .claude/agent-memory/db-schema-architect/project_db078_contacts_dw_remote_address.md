---
name: project-db078-contacts-dw-remote-address
description: DB-078 (2026-10-09) V127 sweep + V128 DROP contacts_dw.remote_address; guard pg_depend + funkcje PL/pgSQL; test pre/M1/M2 przez Flyway target; zmiany w AnonymizeCustomerExtensionTest
metadata:
  type: project
---

V127 (UPDATE ... NULL, `SET LOCAL row_security = off` zeby rola bez BYPASSRLS dostala glosny blad zamiast cichego 0 przy FORCE RLS V116, wynik weryfikowany) i V128 (lock_timeout 10s, DO-guard: pg_depend na kolumnie + pg_proc z contacts_dw/remote_address bez `v_has_contacts_dw_remote_address`, potem DROP COLUMN IF EXISTS). Czytelnikow kolumny brak (grep backend/frontend/voicebot/dw); jedyne odwolania: anonymize_customer V096/V098 z guardem information_schema.

**Why:** pg_depend NIE sledzi cial PL/pgSQL - dlatego dodatkowy skan prosrc.
**How to apply:** pelny lancuch Flyway w testach traci kolumne po V128 - testy wstawiajace do contacts_dw.remote_address (AnonymizeCustomerExtensionTest) trzeba bylo poprawic (licznik contacts_dw = 0). Test `ContactsDwRemoteAddressDropMigrationTest` buduje baze etapami (Flyway target po opisie migracji, CREATE DATABASE TEMPLATE do prob guardu).
