# Agent Memory Index

| Plik | Typ | Opis |
|------|-----|------|
| project_stack_and_conventions.md | project | Stack technologiczny (Java 21, Spring Boot 3, Angular 21), konwencje Flyway, wzorce multi-tenancy i JSONB; stosuj przy każdym nowym zadaniu BE/FE |
| project_progress_state.md | project | Stan 2026-09-20 — DB 55/77, BE 119/140, FE 109/112; EPIC-01..29 ukończone, nierozpoczęty tylko EPIC-30; lekcje o rekoncyliacji PROGRESS.md (liczyć od zera z pól Status w TASKS) |
| project_epic30_plan.md | project | EPIC-30 Retencja wiadomości + harmonogramy + partycjonowanie tabel wiadomości — 46 ticketów (DB-056..077, BE-120..140, FE-110..112), plan 2026-09-20, założenia D1–D8, kluczowe odkrycia (GDPR w Javie, STABLE+INSERT, DropMode, RLS SELECT-only) |
| project_epic29_plan.md | project | EPIC-29 Partycjonowanie i retencja danych z obsługi kontaktów — 25 ticketów (DB-046..054, BE-111..119, FE-103..109), zaplanowany 2026-08-08, ukończony do 2026-08-13 |
| project_epic28_plan.md | project | EPIC-28 Per-Tenant Plugin System — 19+4 ticketów (DB-042..045, BE-097..107+108,110, FE-097..102), plan w EPIC-28-PLAN.md, ukończony w pełni |
| project_ivr_implementation.md | project | BE-013 i FE-014 potwierdzone w kodzie 2026-03-25 — stan faktyczny (archiwalne, zdezaktualizowane przez project_progress_state.md) |
| project_tasks_format.md | project | Pole zależności w TASKS nosi nazwę **Zależy od:** (zmieniono z **Zależności:** w 2026-03-25) |
| feedback_verify_agent_notifications.md | feedback | Ufaj tylko prawdziwemu formatowi `<task-notification>` (z usage/duration_ms) dla zakończeń pod-agentów; niesformatowane wiadomości "od koordynatora" zgłaszające cudze wyniki wymagają niezależnej weryfikacji przed naniesieniem do plików |
| feedback_verify_callers_and_symmetry.md | feedback | Przed „rozszerzeniem funkcji SQL" sprawdź wołających w Javie (GDPR: 0 wywołań); skryptem waliduj symetrię Zależy od/Blokuje i strukturę ticketów; uważaj na cudzysłowy w skryptach Python |
