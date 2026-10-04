---
name: project_epic30_branch_fragmentation
description: EPIC-30 (retencja wiadomości/RODO) jest rozwijany równolegle na kilku siostrzanych gałęziach rozbieżnych od develop — PROGRESS.md/TASKS-*.md na każdej gałęzi widzi tylko swój podzbiór ticketów.
metadata:
  type: project
---

EPIC-30 (retencja wiadomości i partycjonowanie tabel wiadomości, `DESIGN-message-retention-and-partitioning.md`) jest realizowany równolegle na co najmniej trzech gałęziach, każda rozbieżna od `develop` niezależnie (NIE jedna z drugiej):
- `feature/epic-30-social-message-partitioning` — łańcuch DB-064, DB-065, BE-132, BE-133 (RLS + partycjonowanie + klucz złożony + podpięcie do maszynerii dla `social_message`); ukończony i zweryfikowany 2026-10-01 (`mvn verify -pl app`: 2210 testów, 0 porażek).
- `feature/epic-30-be131-be141-be146` — inny podzbiór ticketów (BE-131/BE-141/BE-146 i prawdopodobnie powiązane).
- `feature/epic-30-be142-fe112` — BE-142 (maskowanie PII w `audit_log`, D10) i FE-112; ta gałąź ma już zastosowaną migrację **V098** na żywej bazie dev, której plik NIE istnieje na gałęzi `feature/epic-30-social-message-partitioning` (stąd V099/V100 na tamtej gałęzi, numeracja kolidowałaby przy scaleniu — trzeba wgrać V098 na wspólną bazę PRZED V099).

**Why:** każda gałąź ma własny `PROGRESS.md`/`TASKS-*.md`, zaktualizowany tylko o swój zakres — `PROGRESS.md` na `feature/epic-30-social-message-partitioning` NIE zawiera BE-131/BE-141/BE-146 ani BE-142/FE-112, i to jest oczekiwane, nie błąd.

**How to apply:** przy pracy na JEDNEJ z tych gałęzi aktualizuj tylko jej zakres — nie próbuj "godzić" stanu z innymi gałęziami EPIC-30 (to osobna decyzja scalania, poza zakresem pojedynczej sesji). Przy scalaniu do `develop` ktoś będzie musiał ręcznie zreconciled numerację migracji Flyway (precedens: V092 zajęte przez `feature-socialmedia`, patrz DB-055) i scalić trzy rozbieżne `PROGRESS.md`. Zob. [[reference_tasks_backend_count_quirks]] przy ewentualnym pełnym przeliczeniu liczników po scaleniu.
