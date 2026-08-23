---
name: project_epic29_plan
description: EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów rozbity na 25 ticketów (DB-046..054, BE-111..119, FE-103..109), zaplanowany 2026-08-08
metadata:
  type: project
---

EPIC-29 (Partycjonowanie i retencja danych z obsługi kontaktów) zdekonstruowany z
`DESIGN-data-retention-partitioning.md` (projekt zaakceptowany przez użytkownika) na 25
ticketów wykonawczych, zapisane 2026-08-08.

**Why:** Projekt architektoniczny/DB był już gotowy (dokument w korzeniu repo) i wymagał
przełożenia na konkretne tickety przed startem egzekucji — czysto planistyczny krok, kod nie
był pisany.

**How to apply:** Gdy użytkownik prosi o wykonanie któregokolwiek ticketu z tego epika, pełna
treść (DDL, sygnatury klas, kryteria akceptacji) jest w `TASKS-DATABASE.md` (DB-046..054),
`TASKS-BACKEND.md` (BE-111..119), `TASKS-FRONTEND.md` (FE-103..109) pod modułem
„Partycjonowanie i retencja danych z obsługi kontaktów (EPIC-29)”. Status w `PROGRESS.md` na
dzień planowania: wszystkie 25 ticketów ⬜ (nierozpoczęte).

## Numeracja kontynuowana od (ważne dla przyszłych epików)
- DB: ostatni przed EPIC-29 był DB-045 (EPIC-28) → EPIC-29 zajął DB-046..054
- BE: ostatni przed EPIC-29 był BE-110 (dodatek do EPIC-28, poza standardowym `### BE-108`
  nagłówkiem — patrz niżej) → EPIC-29 zajął BE-111..119
- FE: ostatni przed EPIC-29 był FE-102 (dodatek do EPIC-28) → EPIC-29 zajął FE-103..109
- Migracje Flyway: ostatnia przed EPIC-29 była V081 (`refresh_token_nullable_tenant_id.sql`) →
  EPIC-29 zajął V082..V090 (9 migracji, numeracja z dokumentu projektowego 1:1). Następny epik
  powinien zacząć od DB-055/BE-120/FE-110/V091.

## Decyzje strukturalne kluczowe dla ticketów
- **DB-052 (V088) to fundament epiku** — naprawia realną, potwierdzoną awarię: partycje
  `contact`/`audit_log` kończą się na `2026_05`, od czerwca 2026 dane trafiają do `*_default`.
  Zależy od DB-049/050/051 (rozszerza `create_next_month_partitions()` o nowe tabele), więc w
  numeracji Flyway wychodzi na V088 (nie V082) — ale w grafie zależności blokuje BE-112/114/115.
- `audit_log` świadomie POZA zakresem per-tenant retencji (log platformowy, SUPER_ADMIN,
  24 mies.) — tylko rotacja naprawiana w DB-052.
- DB-054 (V090, poprawka GUC RLS `app.tenant_id`→`app.current_tenant_id` na 4 istniejących
  tabelach V059/V064/V067/V068) jawnie oznaczona jako opcjonalny bonus-fix, nieblokujący.
- `recording_retention_days` MIGRUJE z `tenant.config` JSONB do `tenant_retention_policy`
  (kategoria RECORDINGS) — BE-116 i FE-109 to USUNIĘCIE pola ze starego miejsca, nie dodanie do
  formularza `tenant-edit-modal` (odwrotność dosłownego brzmienia luki z dokumentu
  projektowego). DB-046 backfill musi czytać per-tenant wartość z `tenant.config`, nie wpisywać
  płaskiego defaultu — inaczej cicho nadpisze customizacje klientów.
- Luka wykryta podczas dekompozycji, flagowana w BE-119: `purge_campaign_contact_archive()`
  (V015) nie ma parametru `tenant_id` — jest globalna, nie per-tenant. Podłączenie kategorii
  `CAMPAIGN_DATA` do per-tenant retencji wymaga decyzji (nowa migracja z `p_tenant_id` vs.
  wywołanie z `MIN(retention_months)`) pozostawionej wykonawcy.
- `RECORDINGS` to jedyna kategoria NIE przechodząca przez `RetentionPurgeService` (BE-113) —
  obsługiwana przez rozszerzenie `RecordingRetentionJob` (BE-116), bo to wyzerowanie kolumny +
  S3 delete, nie usunięcie wiersza.

## Ważne odkrycie przy tej dekompozycji: `PROGRESS.md` „Podsumowanie” było nieaktualne
Sekcja „Podsumowanie”/„Nie rozpoczęte wg EPIC” w `PROGRESS.md` wciąż pokazywała EPIC-28 jako
19 ticketów nierozpoczętych (4 DB/11 BE/4 FE), mimo że nagłówek dokumentu (linia 4) i treść
`TASKS-*.md` jednoznacznie potwierdzają pełne ukończenie EPIC-28 — **łącznie z ticketami
BE-108, BE-110, FE-101, FE-102 dodanymi już PO pierwotnym zamknięciu epiku** (BE-108 jest
nietypowy: ma przydzielone ID i jest zaimplementowany, ale NIE ma własnego nagłówka `### BE-108`
w TASKS-BACKEND.md — treść jest wklejona jako addendum w sekcji BE-106; grep po `^### BE-108`
nic nie znajdzie, trzeba szukać `BE-108` w tekście). Ta sama klasa rozbieżności co
wcześniej odnotowana przy EPIC-27 (zob. `[[project_progress_state]]`). **Nie naprawiłem tej
rozbieżności przy dodawaniu EPIC-29** (poza zakresem zlecenia) — dodałem tylko notatkę
ostrzegawczą w `PROGRESS.md` i zwiększyłem liczniki o deltę EPIC-29 na bazie istniejących
(nieaktualnych) liczb. Przy następnej dekompozycji: **zawsze weryfikuj najwyższy numer ticketu
przez `grep`/`sort` bezpośrednio w plikach TASKS-*.md, nigdy nie ufaj liczbom w sekcji
„Podsumowanie” PROGRESS.md** — rozważ też najpierw uruchomienie `/update-progress`, żeby nie
piętrzyć kolejnych niezsynchronizowanych warstw.
