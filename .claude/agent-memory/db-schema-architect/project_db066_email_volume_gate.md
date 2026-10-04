---
name: project-db066-email-volume-gate
description: DB-066 (2026-10-04) — bramka D2 (próg partycjonowania email_message) nierozstrzygnięta; liczby demo, model wzrostu 3 scenariusze, skrypt scripts/epic-30/db-066-email-message-volume.sql, otwarte pytania do PO (D4 = A nie potwierdzone)
metadata:
  type: project
---

DB-066 wykonany jako pomiar read-only (bez migracji, bez zmian DESIGN/PROGRESS). Bramka G1–G4 dla `email_message` **nierozstrzygnięta** — brak liczb prod/stage; czeka na PO.

**Skrypt:** `scripts/epic-30/db-066-email-message-volume.sql` (pierwsza instrukcja `SET default_transaction_read_only = on`, `SET TIME ZONE 'UTC'`, zero DDL/DML). Uruchamiany przez `docker exec -i cc-postgres psql -U ccapp -d contact_center -X -v ON_ERROR_STOP=1 < ...`. Na stage/prod ten sam skrypt, etykieta środowiska w wyniku. Repo nie miało wcześniej katalogu skryptów SQL poza `frontend/scripts`.

**Liczby demo (n=55, 1 tenant z e-mailem, 14 obiektów S3):** total 232 kB; śr. `pg_column_size` wiersza 1 049 B (p95 2 645 B); 16 % wiadomości z załącznikami; `retention_purge_log` 2 przebiegi (CONTACT_INTERACTIONS); D4 — 0 wierszy bez received_at/sent_at, 0 wierszy `received_at ≪ created_at` (skrzynka niezmigrowana), `created−received` p95 63 s.

**Model (założenia):** bez purge, 365 dni, indeks 200 B/wiersz, S3 poza G1. Niski 5×100/dzień → NO-GO (12 mies. ≈0,2 GiB). Średni 20×1000 → G2 po ~100 dniach, G1 po ~168 dniach, G3 tak → GO. Wysoki 50×3000 → progi w ~2 tygodnie → GO. Ogólnie nierozstrzygnięte.

**Why:** ticket wymagał liczb prod; demo nie rozstrzyga; PO nie podał E (e-maile/dzień/tenant), r (rozmiar treści), udziału załączników.

**How to apply:** przy kolejnej rundzie bramki — wziąć odpowiedzi PO (lista pytań w notatce DB-066 w TASKS-DATABASE.md), uruchomić skrypt na stage/prod (rola z BYPASSRLS), przeliczyć model. Nie traktować progów G1–G4 jako rozstrzygniętych. Propozycje kalibracji (nieznanie wprowadzone do DESIGN §3 D2): G2 = 2 mln osiągane za wcześnie na to, że to nie problem wydajności; G4 w 1. roku nieosiągalne przy retencji 60 mies. i auto-purge OFF (V082) → proponowany G5 „retencja krótsza niż wiek najstarszego wiersza". Powiązane: [[project-db065-social-message-partitioning]], [[feedback-measurement-script-rls-tz]].
