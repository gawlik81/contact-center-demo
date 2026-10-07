---
name: project_epic30_branch_fragmentation
description: HISTORYCZNE — EPIC-30 było rozwijane równolegle na siostrzanych gałęziach; wszystkie trzy zostały scalone do `feature/epic-30-email-message-partitioning` do 2026-10-04 (PR #46, #47, #48), ale scalenie USZKODZIŁO TASKS-BACKEND.md. Zob. [[project_be_tasks_backend_corruption_bdc5268]] dla szczegółów uszkodzenia.
metadata:
  type: project
---

EPIC-30 (retencja wiadomości i partycjonowanie tabel wiadomości, `DESIGN-message-retention-and-partitioning.md`) było realizowane równolegle na co najmniej trzech gałęziach, każda rozbieżna od `develop` niezależnie (NIE jedna z drugiej):
- `feature/epic-30-social-message-partitioning` — łańcuch DB-064, DB-065, BE-132, BE-133 (RLS + partycjonowanie + klucz złożony + podpięcie do maszynerii dla `social_message`); ukończony i zweryfikowany 2026-10-01 (`mvn verify -pl app`: 2210 testów, 0 porażek). Zmergowany jako PR #48 (merge commit `f715f06`, 2026-10-04).
- `feature/epic-30-be131-be141-be146` — BE-131 (sweep `pending/` S3), BE-141 (usunięcie `remote_address` z ETL), BE-146 (fix `entity_id` w `AuditAspect`). Zmergowany jako PR #47 (merge commit `78726d4`, 2026-10-01).
- `feature/epic-30-be142-fe112` — BE-142 (maskowanie PII w `audit_log`, D10) i FE-112. Zmergowany jako PR #46 (merge commit `8db5b0b`, 2026-10-01).

**STATUS (2026-10-07, zaktualizowane): wszystkie trzy gałęzie SCALONE** w kolejności PR#46 → PR#47 → PR#48 → (bezpośrednie commity DB-066/070/080, BE-134/135/136) na obecnej gałęzi `feature/epic-30-email-message-partitioning`. Kod ze wszystkich trzech gałęzi jest obecny i poprawny (zweryfikowane grepem w `backend/app/src/main/java`: `PendingAttachmentSweepJob`, `ContactDwRow` bez `remoteAddress`, `AuditAspect` maskowanie PII — wszystkie istnieją). **ALE:** komponent `bdc5268` (2026-10-01, część gałęzi `feature/epic-30-social-message-partitioning`, scalonej jako PR#48) nadpisał `TASKS-BACKEND.md` STARĄ kopią pliku (sprzed PR#46/#47), cichu cofając statusy BE-131/BE-141/BE-142 z `✅` na `⬜` i usuwając jedyną wzmiankę o BE-146 — regresja przetrwała merge i dotrwała do 2026-10-07 niezauważona. Szczegóły, dowód (bisekcja commitów) i sposób odzyskania: [[project_be_tasks_backend_corruption_bdc5268]].

**Why:** scalanie wielu równoległych gałęzi, każda z własnymi edycjami TEGO SAMEGO pliku dokumentacji (`TASKS-BACKEND.md`), bez narzędzia wykrywającego semantyczne konflikty w treści (git merge nie konfliktuje, gdy linie nie nakładają się dosłownie, ale commit `bdc5268` i tak nadpisał cały plik swoją kopią) — klasyczny "lost update" przy wielogałęziowym rozwoju dokumentacji tekstowej.

**How to apply:** ZASADA NA PRZYSZŁOŚĆ — po scaleniu kilku sióstr-gałęzi EPIC-u, PIERWSZA rzecz do zrobienia to grep KAŻDEGO ID ticketu z KAŻDEJ scalonej gałęzi w finalnym pliku i porównanie z oczekiwanym statusem (nie ufać, że merge = sukces semantyczny tylko bo nie ma konfliktu git). Ten epik już nie jest fragmentaryczny (wszystko na jednej gałęzi) — ta lekcja zostaje jako ostrzeżenie dla przyszłych multi-branch epików, nie jako aktualny stan.
