---
name: project-be148-split
description: BE-148 (rola aplikacji bez BYPASSRLS) to PARASOL od tury 34 (2026-10-11); dzieci DB-083, BE-149, BE-150; decyzje właściciela (wariant mieszany, cc_app, ccapp=migracyjna); liczniki 330/345
metadata:
  type: project
---

Od tury 34 (2026-10-11) BE-148 jest ticketem-parasolem (XL, ⬜, WP-1 ✅); `Blokuje` DB-083 (role NOLOGIN/GRANT-y/funkcje SECURITY DEFINER, migracje po V135), BE-149 (HTTP/audyt/login/refresh/lookupy), BE-150 (joby/ETL/routing/dialer). Wszystkie ⬜, EPIC-30 (mianownik 58). Liczniki: DB 78/83, BE 141/150, FE 111/112 = 330/345, ⬜ 8.

**Why:** WP-1 (DESIGN-be148-wp1-analiza-db.md, ...-inwentarz-java.md) wykazał zakres szerszy niż joby; decyzja właściciela: wariant mieszany (cc_app NOBYPASSRLS, ccapp zostaje migracyjną bez REASSIGN OWNED, GUC per tenant, wąskie SECURITY DEFINER z `search_path = public, pg_temp`, osobny DataSource ETL read-only). Prod = własny PG, ccapp = bootstrap-superuser; rls_probe zostaje.

**How to apply:** domknięcie parasola po dzieciach to warunek PROCESOWY, nie zależność (cykl!). Nie wpisuj numerów migracji > max pliku na sztywno (skrypt walidujący to flaguje). Otwarte ryzyko: definer NOSUPERUSER vs DDL na tabelach ccapp (hipoteza: członkostwo w roli właściciela) — pierwszy krok DB-083. Znana, niezwiązana asymetria: DB-081 Zależy od DB-071, a DB-071 nie listuje DB-081 w Blokuje.
