---
name: IVR Engine i edytor FE — stan implementacji
description: BE-013 i FE-014 potwierdzone w kodzie 2026-03-25; PROGRESS.md zaktualizowany
type: project
---

BE-013 (IVR Engine) i FE-014 (Graficzny edytor IVR drag & drop) są faktycznie zaimplementowane w kodzie, mimo że PROGRESS.md przez pewien czas pokazywał je jako ⬜.

**Why:** Implementacje istniały w katalogu projektu, ale nie zostały zaraportowane w PROGRESS.md po poprzednich sesjach deweloperskich.

**Gdzie w kodzie:**
- Backend: `backend/app/src/main/java/com/contactcenter/api/ivr/` (IvrController, DTOs), `domain/ivr/` (IvrDefinition, IvrNode, IvrNodeType, IvrOption, IvrSessionData, IvrCallListener), `domain/service/` (IvrService, IvrEngineService)
- Frontend: `frontend/src/app/features/supervisor/pages/ivr/ivr-editor/` (IvrEditorComponent — canvas SVG drag & drop), `ivr-list/` (IvrListComponent), `supervisor/services/ivr.service.ts`, `supervisor/models/ivr.model.ts`

**How to apply:** Przy kolejnych audytach statusów — zawsze weryfikuj faktyczny stan kodu, nie polegaj tylko na PROGRESS.md. Sprawdzaj katalogi `api/`, `domain/service/` w backendzie i `features/supervisor/pages/` we frontendzie.
