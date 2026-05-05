---
name: Aktualny stan realizacji projektu Contact Center
description: Stan ukończenia zadań DB/BE/FE – aktualizacja 2026-05-05; stosuj przy szacowaniu pozostałych prac i obliczaniu liczników PROGRESS.md
type: project
---

Stan na 2026-05-05: DB: 29/31 | BE: 54/60 | FE: 65/67 (w tym FE-046/047/048 ✅, FE-049-065 z EPIC-19 i18n)

**Why:** Zaktualizowano 2026-05-05 po dodaniu zadań EPIC-20 (Per-tenant konfiguracja Twilio): DB-030, DB-031, BE-055, BE-056, BE-057, BE-058, BE-059, BE-060, FE-066, FE-067. FE-046/047/048 (EPIC-17) okazały się ✅ ukończone na podstawie TASKS-FRONTEND.md. FE-049–FE-065 to EPIC-19 i18n — większość ukończona, FE-062/063/064/065 to ⬜ Do zrobienia.

**How to apply:** Przed tworzeniem nowych zadań sprawdź aktualne numery w plikach TASKS-*.md. Następna migracja Flyway: V053 (V051 i V052 zarezerwowane przez DB-030/DB-031 EPIC-20). Ostatni numer zadań: DB-031, BE-060, FE-067.

## Nowe zadania EPIC-20 (Per-tenant konfiguracja Twilio) – dodane 2026-05-05

### Database (2 nowe, ⬜ Nie rozpoczęte)
- DB-030: Tabela `tenant_twilio_config` (V051__create_tenant_twilio_config.sql) — kredencjały Twilio per tenant z RLS i szyfrowaniem
- DB-031: Kolumna `caller_id` w `campaign` (V052__add_caller_id_to_campaign.sql) — numer prezentacji kampanii

### Backend (6 nowych, ⬜ Nie rozpoczęte)
- BE-055: Encja `TenantTwilioConfig` + `TenantTwilioConfigRepository` + `EncryptedStringConverter` (AES-256-GCM)
- BE-056: `TenantTwilioConfigService` — upsert, masking sekretów, testConnection, TwilioConfigChangedEvent
- BE-057: `TenantTwilioConfigController` — REST API CRUD dla supervisora (GET/PUT/DELETE/test)
- BE-058: Refaktoryzacja `TwilioTelephonyAdapter` na per-tenant z Caffeine cache i fallbackiem
- BE-059: Per-tenant Access Token dla Twilio Voice JS SDK z fallbackiem do globalnych properties
- BE-060: Caller ID dla kampanii — pole callerId w Campaign + propagacja do ProgressiveDialerService

### Frontend (2 nowe, ⬜ Nie rozpoczęte)
- FE-066: `TwilioConfigComponent` — formularz konfiguracji Twilio w panelu supervisora (ReactiveFormsModule, masked inputs, test connection)
- FE-067: Pole "Numer prezentacji" w formularzu kampanii — opcjonalne, E.164 walidacja, tylko dla OUTBOUND_VOICE

## Nieukończone zadania FE z wcześniejszych EPIC

### Frontend (4 nieukończone z EPIC-19 i18n)
- FE-062: i18n fix email-contact i social-contact
- FE-063: i18n fix customer-panel
- FE-064: i18n fix agent-groups, admin-user-list
- FE-065: i18n fix manual-callback-modal i agent-callbacks-page
