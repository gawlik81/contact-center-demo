---
name: project-archive-period-tura35
description: Tura 35 (2026-10-11) — okres archiwizacji kontaktów kampanii konfigurowalny per tenant (DB-084 → BE-151 → FE-113), flaga BE-120 w demo cofnięta; liczniki 330/348
metadata:
  type: project
---

Tura 35 założyła DB-084 (M, model danych per tenant jako WP-1, domyślnie 180 dni, CHECK 30–730, decyzja `updated_at` vs `ended_at` NIEROZSTRZYGNIĘTA), BE-151 (M, API polityk retencji + CampaignArchiveJob), FE-113 (S, pole na stronie Retencja danych). Wszystkie ⬜, EPIC-30 mianownik 61. Liczniki: DB 78/84, BE 141/151, FE 111/113, RAZEM 330/348, ⬜ 11.

**Why:** okres 30 dni był zaszyty w V015; właściciel (2026-10-11) chce go w GUI per tenant. Domyślne 180 dni ZMIENIA zachowanie 30 → 180 (zapisane jako świadome ryzyko DB-084 R1). Zgoda z tury 31 na flagę w demo COFNIĘTA (flaga false; WP-4 BE-120 dopiero po DB-084/BE-151).

**How to apply:** BE-120 `Blokuje` DB-084 (bez ✅), BE-120 ✅ bez zmian. DB-084 nie czeka na BE-148/DB-083, ale modyfikuje tę samą funkcję co DB-083 pkt 3(v) — kto drugi, bazuje na `pg_get_functiondef`. Nie wpisuj "V136" w ticketach (skrypt HARDMIG flaguje). Skrypt walidujący: verify35.py (kopia verify34.py ze zmienionym zbiorem ticketów; źródła migracji w `backend/src/main/resources/db/migration`, NIE `backend/app/...`).
