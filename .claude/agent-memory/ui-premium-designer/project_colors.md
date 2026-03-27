---
name: Color Palette Inconsistencies
description: Brand blue ma trzy różne wartości w projekcie — mapa kolorów per komponent
type: project
---

# Mapa kolorów projektu (stan 2026-03-26)

**Why:** Znaleziono 3 różne wartości "brand blue" podczas analizy UI — każda zmiana koloru wymaga wiedzy o tej rozbieżności.

## Brand blue variants
- `#1565c0` — login, admin-dashboard, tenant-list, queue-list, user-list (główna wartość)
- `#1a56db` — sidenav focus ring, skip-link, breadcrumbs (jaśniejszy odcień)
- `#4a90d9` — IVR editor (celowo inny — strefa canvas, zachować)

## Intencjonalne wyjątki
- IVR editor używa `#4a90d9` jako accent — jest to CELOWE by odróżnić środowisko edytora od reszty UI
- Email contact używa `#3b82f6` (Tailwind blue-500) dla przycisków — inconsistent

## Tło strony
- App shell: `#f8fafc` (slate-50)
- Supervisor dashboard: `#f5f5f5` (gray-100) — lekko inna!
- Login: `#f0f4f8` — jeszcze inna!

## How to apply
Przy tworzeniu nowych komponentów używaj `#1565c0` jako brand blue. Przy zmianach w IVR editor zachowaj `#4a90d9`. Przy poprawkach supervisor-dashboard dopasuj tło do `#f8fafc`.
