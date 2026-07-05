---
name: project_epic28_plan
description: EPIC-28 Per-Tenant Plugin System rozbity na 19 ticketów (DB-042..045, BE-097..107, FE-097..100), plan w EPIC-28-PLAN.md, status zaplanowany 2026-06-20
metadata:
  type: project
---

EPIC-28 (Per-Tenant Plugin/Extension System) został zdekonstruowany z ARCHITECTURE.md §11
(ADR-09..13, RT-09..14) na 19 ticketów wykonawczych, zapisane 2026-06-20.

**Why:** Architektura była już zaprojektowana (zob. notatkę architecture-designer
`.claude/agent-memory/architecture-designer/epic_28_plugin_system.md`) i wymagała przełożenia
na konkretne tickety przed startem egzekucji — to czysto planistyczny krok, kod nie był pisany.

**How to apply:** Gdy użytkownik prosi o wykonanie któregokolwiek ticketu z tego epika, plan
wykonania (fale, zależności, agent per ticket) jest w `/home/pawelm/contact-center/EPIC-28-PLAN.md`.
Pełna treść ticketów (DDL, sygnatury klas, kryteria akceptacji) jest w `TASKS-DATABASE.md`
(DB-042..045), `TASKS-BACKEND.md` (BE-097..107), `TASKS-FRONTEND.md` (FE-097..100) pod modułem
"Per-Tenant Plugin (Extension) System (EPIC-28)". Status w `PROGRESS.md` na dzień planowania:
wszystkie 19 ticketów ⬜ (nierozpoczęte). Zob. też `[[project_progress_state]]`.

## Numeracja kontynuowana od (ważne dla przyszłych epików)
- DB: ostatni przed EPIC-28 był DB-041 (EPIC-27) → EPIC-28 zajął DB-042..045
- BE: ostatni przed EPIC-28 był BE-096 (EPIC-27) → EPIC-28 zajął BE-097..107
- FE: ostatni przed EPIC-28 był FE-096 (EPIC-27) → EPIC-28 zajął FE-097..100
- Migracje Flyway: ostatnia przed EPIC-28 była V073 (`add_ivr_contact_status.sql`) → EPIC-28
  zajął V074..V077. Następny epik powinien zacząć od DB-046/BE-108/FE-101/V078.

## Decyzje strukturalne kluczowe dla ticketów
- `plugin`/`plugin_version` (DB-042) są GLOBALNE — bez `tenant_id`, bez RLS (ADR-13). Pozostałe
  trzy tabele epika (DB-043/044/045) SĄ tenant-scoped z RLS+FORCE RLS.
- BE-101 (`PluginRuntimeManager`+`PluginClassLoader`) oznaczony jako XL i najwyższe ryzyko
  bezpieczeństwa epika (RT-10) — w kryteriach akceptacji wymagany obowiązkowy code review przez
  `senior-code-reviewer` przed merge, jedyny taki wymóg w tym epiku.
- Nowy moduł Maven `backend/plugin-sdk` (BE-097) musi być dodany jako `<module>` w root
  `backend/pom.xml` obok istniejącego `<module>app</module>` — zero zależności Spring/JPA.
- Frontend FE-099 ma kluczowy wymóg bezpieczeństwa: `sandbox="allow-scripts allow-forms"`
  BEZ `allow-same-origin` na iframe — to jest testowalne kryterium akceptacji, nie tylko opis.

## Niepewności zostawione jako "known-gap" w treści ticketów (nie blokują planowania)
- FE-098: czy `PluginVersionDto` z BE-099 eksponuje pełną listę `permissions` z manifestu do
  dialogu instalacji — flagowane do doprecyzowania przy egzekucji, nie zatrzymuje planu.
- FE-100: czy potrzebny jest osobny, lżejszy endpoint `GET /api/agent/plugins` (rola AGENT) czy
  reużyć `/api/supervisor/plugins` (rola SUPERVISOR) — flagowane jako możliwy follow-up BE ticket.
- BE-107: serwowanie assetów z dedykowanej originy może wymagać zmiany konfiguracji
  DNS/reverse-proxy poza zakresem czysto-kodowym — flagowane dla DevOps jeśli się okaże potrzebne.
