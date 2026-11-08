---
name: tura74-dopisek2
description: Tura 74 dopisek 2 (2026-11-08) — BE-209, FE-163 zamknięte; FE-149 skorygowane z powrotem na ⬜ (częściowo w FE-163); nowe BE-218/219/220, FE-167; liczniki 398/88/7=493, następne BE-221/FE-168/DB-099
metadata:
  type: project
---

Tura 74 dopisek 2 (gałąź `fix/etl-watermark-and-agent-presence`, niezacommitowane; poprzednik [[tura74-etl-presence]]). ✅ BE-209 (warunkowe zapisy kontaktów, 409 zamiast cichego nadpisania), ✅ FE-163 (backend źródłem prawdy, lista nadmiaru, `MAX_TOTAL_TABS=4` z rezerwą PHONE), FE-149 (KOREKTA 2026-11-08: wcześniej błędnie ✅, wraca na ⬜ „częściowo zrealizowane w FE-163”; `[x]` zostały tylko przy przycisku per pozycja, 404/409/błędach+testach+i18n, RINGING/PHONE; `[ ]` przy WP-1…WP-7 i potwierdzeniu z FE-132; zostaje weryfikacja WP na local-demo i potwierdzenie z FE-132). Nowe ⬜: BE-218 (`CONTACT_ASSIGNED` bez assignedAt/queueId), BE-219 (ACTIVE bez karty — wpływ na SLA/metryki, wymaga decyzji), BE-220 + FE-167 (sygnał zdolności telefonicznej → warunkowa rezerwa PHONE). Liczniki: DB 87/8/3=98, BE 164/57/3=224, FE 147/23/1=171, RAZEM 398/88/7=493; EPIC-32 FE 35/18/53; tabela TASKS-FRONTEND „Razem” była nieaktualna (146/23/170) — poprawiona na 147/0/23/1/171. Następne wolne: BE-221, FE-168, DB-099. Weryfikacja na local-demo nadal otwarta.

**Why:** punkt (c) zadany jako "BE+FE" rozbity na dwa tickety (jeden właściciel na ticket).
**How to apply:** skrypt po `###` (FE ✅ = 147 po korekcie) (z T00x jako ⬜, BE-067 `[x]` jako ✅) daje BE 162 ✅ + 2 (BE-108/109 bez nagłówka) = 164; ⬜ 57 zawiera 4 BE-T00x. Skrypt edycji t74d.py nie jest idempotentny.

**Lekcja:** nie oznaczaj ✅ ticketu „w ramach” innego, dopóki kryteria WP/UX nie zweryfikowane kodem lub w przeglądarce; sprawdź też wiersz „Razem” w tabeli indeksu TASKS-FRONTEND (potrafi zostać nieaktualny po dopisku).
