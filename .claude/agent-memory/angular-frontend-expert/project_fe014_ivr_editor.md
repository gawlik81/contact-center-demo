---
name: IVR editor drag & drop (FE-014)
description: Graficzny edytor drzewa IVR z drag & drop, SVG połączeniami i panelem właściwości
type: project
---

Zaimplementowano FE-014 – graficzny edytor drzewa IVR.

**Why:** Supervisor musi móc wizualnie budować drzewa IVR bez znajomości JSON.

**How to apply:** Przy rozszerzaniu edytora IVR korzystaj z tych wzorców.

## Pliki

- `src/app/features/supervisor/models/ivr.model.ts` – modele IvrNode, IvrDefinition, IvrNodeUI (z x/y), IvrResponse, CreateIvrRequest, UpdateIvrRequest, DEMO_IVR
- `src/app/features/supervisor/services/ivr.service.ts` – CRUD /api/ivr, zarządzanie pozycjami węzłów w localStorage (klucz `ivr:positions:{ivrId}`), toUIDefinition(), toApiDefinition(), generateNodeId()
- `src/app/features/supervisor/pages/ivr/ivr-list/` – lista IVR z skeleton, badge AKTYWNE/NIEAKTYWNE, modal create, modal potwierdzenia usunięcia
- `src/app/features/supervisor/pages/ivr/ivr-editor/` – główny edytor

## Kluczowe decyzje edytora

- Canvas: `position: relative` div 3000x2000px z węzłami jako `position: absolute`
- SVG overlay (pointer-events: none) z ścieżkami Beziera jako połączenia między węzłami
- Drag węzłów: `mousedown` na węźle → śledzenie delty w `onCanvasMouseMove` (nie HTML5 dragstart – to zarezerwowane dla toolbar→canvas)
- Drag z toolbar: HTML5 Drag and Drop API, `dragstart` ustawia `dataTransfer` z typem węzła
- Port wyjściowy: kliknięcie ustawia `connectingFrom` signal; kolejne kliknięcie węzła docelowego tworzy połączenie
- Upload audio: mock (setTimeout 1.5s fake progress), audio_id = `mock-audio://{filename}`
- Debug JSON panel: collapsible, `apiDefinitionJson` computed signal
- Walidacja: validateDefinition() sprawdza entry node, opcje MENU/COLLECT_DTMF, wiszące połączenia, nieosiągalne węzły (BFS)
- Wersjonowanie: wersja z backendu odświeżana po każdym PATCH response
- Pozycje węzłów: localStorage, klucz `ivr:positions:{ivrId}`, strip x/y przed wysłaniem do API

## Routing

Dodano do `supervisor.routes.ts` (dzieci pod SupervisorShellComponent):
- `/supervisor/ivr` → IvrListComponent (canActivate: roleGuard, roles: ['SUPERVISOR', 'ADMIN'])
- `/supervisor/ivr/:ivrId` → IvrEditorComponent

Dodano pozycję "IVR" do SUPERVISOR_NAV w sidenav.component.ts (przed Konfiguracją).
