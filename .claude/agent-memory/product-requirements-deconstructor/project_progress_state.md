---
name: Aktualny stan realizacji projektu Contact Center
description: Stan ukończenia zadań DB/BE/FE – aktualizacja 2026-05-24; stosuj przy szacowaniu pozostałych prac i obliczaniu liczników PROGRESS.md
type: project
---

Stan na 2026-05-24: DB: 39/39 ✅ | BE: 92/92 ✅ | FE: 75/75 ✅ — WSZYSTKIE ZADANIA UKOŃCZONE (206/206)

**Why:** Zaktualizowano 2026-05-24 po zrealizowaniu EPIC-26 (AI-Powered Conversation Summary):
- DB-038: V064 tabela tenant_ai_config (ENUM ai_provider, RLS, szyfrowanie AES-256-GCM) + V066 ADD VALUE 'OPENROUTER'
- DB-039: V065 kolumny ai_summary, ai_summary_model, ai_summary_generated_at w tabeli contact
- BE-086: AiProvider enum, TenantAiConfig JPA, TenantAiConfigRepository
- BE-087: TenantAiConfigService (upsert, masking apiKey, getDecryptedConfig), DTOs, 15 testów
- BE-088: TenantAiConfigController GET/PUT/DELETE /api/supervisor/ai-config (SUPERVISOR)
- BE-089: AiSummaryClient (HTTP java.net.http, 30s timeout), AiSummaryService, GlobalExceptionHandler (422/502), 8 testów
- BE-090: endpoint POST /api/contacts/{contactId}/ai-summary (AGENT/SUPERVISOR), AiSummaryResponse DTO
- BE-091: Python voicebot endpoint /ai/summarize, moduł summarize.py (Anthropic/OpenAI/Azure/OpenRouter dispatcher), 9 testów pytest
- FE-086: AiSummaryService (generateSummary, AiConfigNotSetError 422, AiServiceUnavailableError 502)
- FE-087: sekcja AI na DispositionPanelComponent (sygnały, spinner, textarea, error handling)
- FE-088: AiConfigService, AiConfigComponent (4 providerzy, masking klucza, pola Azure warunkowe), routing /supervisor/settings/ai-config
- FE-089: AiSummaryPanelComponent (shared standalone), refaktor DispositionPanelComponent, integracja z EmailContactComponent

**How to apply:** Projekt Contact Center SaaS jest w pełni zaimplementowany — 206/206 zadań. Przy dodawaniu nowych tasków numeruj od DB-040, BE-092, FE-090. Następna migracja Flyway: V067.

## Ostatnie ukończone EPIC (chronologicznie)

- EPIC-17 (Incoming Call Alert): FE-046, FE-047, FE-048 — 2026-04-28
- EPIC-19 (Wielojęzyczność): DB-029, BE-054, FE-049–FE-065 — 2026-04-28 do 2026-05-03
- EPIC-20 (Per-tenant Twilio config): DB-030, DB-031, BE-055–BE-061, FE-066–FE-068 — 2026-05-05 do 2026-05-07
- EPIC-25 (Kampanie — refaktor i transfer): DB-032–DB-037, BE-062–BE-085, FE-069–FE-085 — 2026-05-08 do 2026-05-23
- EPIC-26 (AI-Powered Conversation Summary): DB-038–DB-039, BE-086–BE-091, FE-086–FE-089 — 2026-05-24
