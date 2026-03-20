---
name: Aktualny stan realizacji projektu Contact Center
description: Stan ukończenia zadań DB/BE/FE oraz ostatnie implementacje – 2026-03-20
type: project
---

Stan na 2026-03-20: DB: 19/19 ✅ | BE: 14/31 | FE: 13/24

**Ukończone BE:** BE-001..BE-012, BE-025, BE-027
**Ukończone FE:** FE-001..FE-011, FE-017, FE-018

**Ostatnie implementacje (2026-03-20):**
- BE-027: ContactController (6 endp.), ContactService (CRUD + uprawnienia AGENT/SUPERVISOR/ADMIN), ContactRepository (native INSERT/UPDATE, partycjonowana tabela), ContactId.java, DTOs (ContactResponse/CreateContactRequest/UpdateContactRequest/DispositionRequest/ContactFilterParams), 22 testy PASS, build 365/365 PASS
- FE-017: DispositionPanelComponent (modal ACW, timer MM:SS, dropdown 6 kodów, textarea notatka), ContactService.setDisposition() → PATCH /api/contacts/{id}/disposition, contact-tab.store.ts (stan WRAPPING + markAsWrapping()), effect() na session.state=ENDED w agent-desktop
- FE-011: Panel boczny w AgentDesktopComponent z danymi klienta (CLI lookup), historia kontaktów, CTA dla nieznanych numerów; integracja z BE-025 ✅ i BE-011 ✅

**Następne priorytety (odblokują najwięcej):**
1. FE-019 (Profil klienta) – wszystkie zależności spełnione: FE-018 ✅ + BE-025 ✅ + BE-027 ✅
2. BE-020 (Queue API) – odblokuje FE-024
3. BE-022 (Campaign CRUD) – odblokuje FE-015, FE-016
4. BE-028 (Raporty historyczne) – odblokowane przez BE-027 ✅

**Why:** Stan regularnie aktualizowany po każdej sesji implementacji.
**How to apply:** Używaj do odpowiedzi na pytania o postęp projektu; weryfikuj z PROGRESS.md jeśli minęło dużo czasu.
