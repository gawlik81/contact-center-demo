---
name: Campaign CRUD API (BE-022)
description: Zaimplementowany Campaign CRUD API – encja, repozytorium, serwis, kontroler, migracja V026
type: project
---

BE-022 zaimplementowany 2026-03-22.

Pliki:
- `backend/src/main/resources/db/migration/V026__convert_campaign_enum_types_to_varchar.sql`
- `backend/app/.../domain/model/Campaign.java`
- `backend/app/.../domain/repository/CampaignRepository.java`
- `backend/app/.../domain/service/CampaignService.java`
- `backend/app/.../api/campaign/CampaignController.java`
- `backend/app/.../api/campaign/dto/CreateCampaignRequest.java`
- `backend/app/.../api/campaign/dto/UpdateCampaignRequest.java`
- `backend/app/.../api/campaign/dto/CampaignResponse.java`

**Why:** V026 wymagana bo typy `campaign_type`, `dialer_type`, `campaign_status`, `campaign_contact_status` zostały jako PostgreSQL ENUM w V009, a V019 ich nie konwertował. Hibernate 6 binduje String jako VARCHAR co powoduje błąd JDBC bez konwersji.

**How to apply:** Przy tworzeniu nowych encji z ENUM-ami PostgreSQL zawsze sprawdź V019 i V025 – jeśli dany typ nie był konwertowany, dodaj nową migrację V0xx__convert_*_enum_types_to_varchar.sql.

Przejścia statusów kampanii:
- DRAFT → SCHEDULED (start, start_date w przyszłości)
- DRAFT → RUNNING (start, harmonogram pusty lub aktywny)
- SCHEDULED → RUNNING (start)
- PAUSED → RUNNING (start/resume)
- RUNNING → PAUSED (pause)
- RUNNING/PAUSED/SCHEDULED → STOPPED (stop)
- InvalidOperationException (HTTP 409) dla niedozwolonych przejść

InvalidOperationException mapuje na HTTP 409 (nie 422!) – sprawdź GlobalExceptionHandler.
