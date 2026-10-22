---
name: reference_tasks_backend_count_quirks
description: TASKS-BACKEND.md i TASKS-FRONTEND.md mają placeholder-tickety (BE-T00x/FE-T00x) z nagłówkiem ale status 🔲 — wyklucz je, resztą jest bezpieczne pełne przeliczenie. BE-067 ma nietypowy zapis statusu. Status pola może być FAŁSZYWY po wadliwym merge (zob. link).
metadata:
  type: reference
---

**Skorygowane 2026-10-07 (tura 13), zweryfikowane precyzyjnym skryptem na gałęzi `feature/epic-30-email-message-partitioning`:** poprzednia wersja tej notatki (2026-10-01, gałąź `feature/epic-30-social-message-partitioning`) twierdziła, że są DWA zduplikowane nagłówki (`### BE-001`, `### BE-030` x2) — **NIE POTWIERDZONE w tej turze**: `grep -n "^### BE-" TASKS-BACKEND.md | sed ... | sort | uniq -c` nie znajduje ŻADNEGO ID z licznością >1 (149 nagłówków, 149 unikalnych ID). Albo duplikaty istniały tylko na tamtej konkretnej gałęzi/w tamtym momencie i zostały naprawione przy scaleniu, albo poprzednia obserwacja była błędna — **nie przyjmować bez ponownej weryfikacji na aktualnej gałęzi**.

**Metoda przeliczenia, zweryfikowana poprawna 2026-10-07 (DB, BE, FE wszystkie dały wynik zgodny z oczekiwaniem):**
- `TASKS-DATABASE.md`: `grep -c "^### DB-"` = liczba ticketów, 1:1 ze statusami, ŻADNYCH placeholderów. Bezpieczne pełne przeliczenie zawsze.
- `TASKS-FRONTEND.md`: `grep -c "^### FE-"` = 116, ale 4 to `FE-T001..T004` (status `🔲 Do zrobienia`, placeholdery testów jednostkowych, NIE część głównej numeracji FE-001..FE-112) → 116 − 4 = **112**, zgodne z PROGRESS.md.
- `TASKS-BACKEND.md`: `grep -c "^### BE-"` = 149, z czego 4 to `BE-T001..T004` (sam wzorzec co FE-T) → 149 − 4 = **145**, zgodne z PROGRESS.md. **Żadnych innych korekt (duplikaty, dodatkowe wyłączenia) nie są potrzebne** — to jest kompletne wyjaśnienie różnicy 149 vs 145.
- `BE-067` ma nietypowy zapis statusu `[x] Zrobione` (nie `✅`) — licz jako ukończony (`'[x]' in status_line`).
- **Pole `**Status:**` bywa wcięte spacją** (zob. BE-136 przed naprawą 2026-10-07 — `' **Status:** 🚫 N/A...'` z wiodącą spacją) — parser MUSI robić `.strip()` linii przed sprawdzeniem `.startswith('**Status:**')`, inaczej ticket zostanie pominięty jako „brak statusu" i wynik będzie fałszywie niższy o 1.

**Why:** poprzednia notatka (tura wcześniejsza, inna gałąź) myliła DWIE różne rzeczy: prawdziwe placeholdery (BE-T00x/FE-T00x, zawsze wyłączone) z rzekomymi duplikatami (nigdy niepotwierdzonymi na tej gałęzi). Osobny, poważniejszy problem — status pola może być FAŁSZYWY (nie tylko źle sformatowany) po wadliwym scaleniu wielu gałęzi dokumentacji — opisany w `[[project_be_tasks_backend_corruption_bdc5268]]` (BE-131/BE-141/BE-142 pokazują ⬜ mimo zaimplementowanego kodu). To NIE jest problem formatu/parsowania, więc żaden skrypt licznikowy go nie wykryje — tylko grep kodu w `backend/app/src/main/java` przeciw wątpliwym ⬜.

**How to apply:** pełne przeliczenie od zera jest BEZPIECZNE dla wszystkich trzech plików, o ile (1) wykluczysz `BE-T00x`/`FE-T00x` przez wzorzec nagłówka, (2) `.strip()` linii przed testem `**Status:**`, (3) policzysz `[x]` jako ✅ dla BE-067. Metoda delty (poprzednie zalecenie tej notatki) zostaje PRZYDATNA, gdy nie masz czasu na pełne przeliczenie, ale NIE jest już „bezpieczniejsza" niż pełne przeliczenie — w turze 13 obie metody dały TEN SAM wynik dla BE/DB, co potwierdza, że delta i fresh-count są tu równoważne. Niezależnie od metody: jeśli jakiś ticket „wydaje się" niezgodny ze swoim kontekstem (blokuje coś gotowego, inny ticket już go cytuje jako ✅), **zawsze grepuj kod**, nie tylko inne tickety — patrz `[[project_be_tasks_backend_corruption_bdc5268]]`.
