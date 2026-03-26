---
name: Aktualny stan realizacji projektu Contact Center
description: Stan ukończenia zadań DB/BE/FE oraz ostatnie implementacje – 2026-03-26
type: project
---

Stan na 2026-03-26 (pełne skanowanie kodu): DB: 20/20 ✅ | BE: 25/33 | FE: 22/25 | RAZEM: 67/78

**Ukończone BE (25):** BE-001, BE-001b, BE-002, BE-003, BE-004, BE-005, BE-006, BE-007, BE-008, BE-009, BE-010, BE-011, BE-012, BE-013, BE-015, BE-016, BE-019, BE-020, BE-022, BE-023, BE-025, BE-026, BE-027, BE-028, BE-029
**Ukończone FE (22):** FE-001..FE-012, FE-014, FE-015, FE-016, FE-017, FE-018, FE-019, FE-020, FE-021, FE-022, FE-024

**Nieukończone BE (8):** BE-014 (Voicebot Python), BE-017 (OAuth social), BE-018 (Social Adapter), BE-021 (Wait time), BE-024 (Progressive Dialer), BE-030 (ETL DW), BE-031 (RODO export), BE-032 (Twilio per-tenant)
**Nieukończone FE (3):** FE-013 (Social contact), FE-023 (Social OAuth panel), FE-025 (Twilio per-tenant config)

**Korekty z 2026-03-26 (scan kodu):**
- DB-020 (V029) dodane: `V029__add_email_address_to_queue.sql` – kolumna `email_address VARCHAR(255) NULL` w tabeli `queue`, UNIQUE (tenant_id, email_address), partial index WHERE IS NOT NULL. Routing priorytetowy w EmailRoutingService po adresie kolejki przed regułami.
- BE-015 zaktualizowane: encje przeniesione do `domain/model/` (EmailMessage, EmailRoutingRule, EmailTemplate) i repozytoria do `domain/repository/`. EmailRoutingService używa `queueRepository.findByEmailAddressAndTenantId()`.
- FE-012 (Email contact) potwierdzony w kodzie: `email-contact.component.ts`, `email-thread-message`, `email.service.ts` (agent), `email-settings.component.ts` (supervisor), `email-config.service.ts`. EmailSettingsComponent na trasie /supervisor/settings z test połączenia.
- FE-024 (Konfiguracja kolejek) zaktualizowane: QueueFormComponent zawiera pole `emailAddress` (Validators.email, maxLength(255)), model queue.model.ts zawiera `emailAddress?: string | null`.

**Następne priorytety (odblokują najwięcej):**
1. BE-017 (OAuth social) → odblokuje BE-018 i FE-023
2. BE-018 (Social Adapter) → odblokuje FE-013
3. BE-024 (Progressive Dialer) – zależności BE-009 ✅, BE-022 ✅ spełnione
4. BE-031 (RODO export) – zależności BE-025 ✅, BE-027 ✅ spełnione
5. BE-032 (Twilio per-tenant) – zależności BE-009 ✅, BE-006 ✅ spełnione → odblokuje FE-025

**Why:** Stan regularnie aktualizowany po każdej sesji implementacji.
**How to apply:** Używaj do odpowiedzi na pytania o postęp projektu; weryfikuj z PROGRESS.md jeśli minęło dużo czasu.
