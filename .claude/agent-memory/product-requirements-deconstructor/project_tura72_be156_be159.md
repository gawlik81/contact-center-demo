---
name: project-tura72-be156-be159
description: Tura 72 (2026-11-07) - BE-159 i BE-156 zamkniete, DB-096 (V147) i DB-097 (V148) nowe jako ok, nowe BE-207/208/209 i FE-163, liczniki 391/79/7 z 477
metadata:
  type: project
---

BE-156 ✅ i BE-159 ✅ 2026-11-07 (gałąź `fix/routing-conditional-assignment-and-limits`, niezacommitowane, CR 4/5). Nowe od razu ✅: DB-096 (V147, domyślny limit 3, 1→3), DB-097 (V148, indeks `idx_contact_open_async_count`). Nowe ⬜: FE-163, BE-207, BE-208, BE-209. Liczniki: DB 87/7/3=97, BE 159/51/3=213, FE 145/21/1=167, RAZEM 391 ✅ / 79 ⬜ / 7 🚫 = 477. Następne wolne: BE-210, FE-164, DB-098.

**Why:** DB-088 jest przez V148 tylko CZĘŚCIOWO zrealizowany (zostaje ACW i wariant C) - zostaje ⬜. Guard BYPASSRLS w V147 poza zakresem, uwaga w BE-148 i DB-083 (migracje danych pod FORCE RLS po przejściu na cc_app dotkną 0 wierszy bez błędu).
**How to apply:** skrypt po `### XX-NNN` daje BE 157/47/3, FE 145/17/1 - dodaj 4 ⬜ BE-T00x, 4 ⬜ FE-T00x i 2 ✅ BE-108/109. Migracje leżą w `backend/src/main/resources/db/migration` (nie `backend/app/...`). Stan żywej bazy V147/V148 NIE sprawdzony (psql z hosta niedostępny, rola postgres nie istnieje w kontenerze). Niespójności: indeks EPIC-32 w TASKS-BACKEND miał 13/16/29 (brakowało BE-200) - skorygowany do 16/16/32; w PROGRESS stary blok EPIC-32 miał zdublowany wiersz BE-157 ze statusem ⬜. W CR should-fix Javadoc CHAT i luki testowe pkt 1-3 potwierdzone jako RESOLVED 2026-11-07 (pkt 4 WONTFIX).
