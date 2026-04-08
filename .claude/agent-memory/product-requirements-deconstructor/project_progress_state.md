---
name: Aktualny stan realizacji projektu Contact Center
description: Stan ukończenia zadań DB/BE/FE – pełny scan 2026-04-08, stosuj przy szacowaniu pozostałych prac
type: project
---

Stan na 2026-04-08: DB: 20/22 | BE: 30/38 | FE: 25/30 | RAZEM: 75/90

**Why:** Regularnie aktualizowany po pełnym przeglądzie kodu i git log. Poprzedni stan: 74/84 (przed dodaniem EPIC-12).

**How to apply:** Przed tworzeniem nowych zadań lub raportem postępu sprawdź ten plik, żeby znać aktualny punkt startowy.

## Nieukończone zadania (16)

### Database (2)
- DB-021 – Tabele PHONE_NUMBER i PHONE_ROUTING_RULE (EPIC-11, routing numerów telefonicznych)
- DB-022 – Indeksy wyszukiwania kontaktów: idx_contact_queue_date + idx_contact_duration (EPIC-12, migracja V035)

### Backend (8)
- BE-017 – OAuth flow i zarządzanie tokenami social media (czeka na DB-008)
- BE-018 – Social Media Adapter (Facebook/Instagram/WhatsApp, czeka na BE-017)
- BE-030 – ETL do data warehouse: CDC z PostgreSQL (Debezium → ClickHouse)
- BE-031 – RODO: eksport danych klienta (Art. 15) i anonimizacja (Art. 17)
- BE-033 – PhoneNumber CRUD API (EPIC-11, czeka na DB-021)
- BE-034 – PhoneRoutingRule CRUD API (EPIC-11, czeka na BE-033)
- BE-035 – Incoming call routing per numer (EPIC-11, czeka na BE-034)
- BE-036 – Rozszerzenie Contact API o filtry zaawansowane (EPIC-12, czeka na DB-022)

### Frontend (5)
- FE-013 – Komponent obsługi kontaktu social media (czeka na BE-018)
- FE-023 – Panel konfiguracji integracji social media OAuth (czeka na BE-017)
- FE-026 – Panel zarządzania numerami telefonów i regułami routingu IVR (czeka na BE-033, BE-034)
- FE-028 – Modal szczegółów kontaktu + AudioPlayerComponent (EPIC-12, BE-037 ✅ odblokowane — czeka tylko na implementację FE)
- FE-029 – Strona Raporty > Kontakty (EPIC-12, czeka na BE-036, FE-028)
- FE-030 – Integracja szczegółów kontaktu w CustomerDetailComponent (EPIC-12, czeka tylko na FE-028)

## Ukończone od 2026-03-26 (7 tasków)
- BE-014 (2026-04-02) – Voicebot Python: FastAPI, Whisper ASR, keyword NLU, Redis session, RabbitMQ escalation
- BE-024 (2026-04-03) – Progressive Dialer: ProgressiveDialerService, DialerCallbackHandler, ScheduledCallback
- BE-032 (2026-04-01) – Twilio config per tenant: getTwilioPhoneNumber(), resolvePhoneNumber(), PATCH /api/tenants/{id}/config
- BE-037 (2026-04-08) – GET /api/contacts/{id}/recording → ContactRecordingUrlResponse (presignedUrl TTL 15min, expiresAt, fileName, durationSeconds). generatePresignedUrlForKey w RecordingService. 8 testów.
- FE-025 (2026-04-01) – Panel Twilio per tenant: TwilioSettingsComponent, TwilioConfigService
- FE-027 (2026-04-08) – Przycisk „Zadzwoń" dialera manualnego: ManualCampaignPanelComponent, DialerService.callRecord

**Priorytety (odblokują najwięcej):**
1. FE-028 → FE-029, FE-030 (EPIC-12 modal + nagrania – BE-037 ✅ odblokowane, tylko praca FE pozostała)
2. DB-022 → BE-036 → FE-029 (EPIC-12 Raporty Kontakty – szybka ścieżka)
3. DB-021 + BE-033 → BE-034 → BE-035 + FE-026 (EPIC-11 routing numerów)
4. BE-017 → BE-018 → FE-013 + FE-023 (Social Media – EPIC-06)
5. BE-031 (RODO export – zależności spełnione)
