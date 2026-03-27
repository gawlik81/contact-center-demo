---
name: Component Quality Rankings
description: Ranking jakości komponentów z UI review 2026-03-26 — od najlepszego do wymagającego refaktoru
type: project
---

# Ranking jakości komponentów (UI Review 2026-03-26)

**Why:** Analiza pozwala priorytetyzować pracę i wskazać wzorce do naśladowania.

## Ranking (od najlepszego)

| Komponent | Ocena | Uwagi |
|-----------|-------|-------|
| Softphone | 8/10 | Najlepszy — scale effects, state gradients, pulse animation |
| IVR Editor | 7.5/10 | Dot-grid canvas, color-coded nodes, dash-flow animation |
| Agent Desktop | 7/10 | Color-coded tabs, gradient avatar, WS indicator |
| Admin Dashboard | 7/10 | Skeleton loaders, icon wraps, KPI structure |
| Email Contact | 6.5/10 | Split layout, custom scrollbar, focus-within |
| Sidenav | 6.5/10 | Responsive, will-change, focus rings |
| TopNavbar | 6/10 | Dobry logout hover, ale słaby brand |
| Login | 5/10 | Płaskie tło, brak entrance animation |
| TenantList/QueueList | 5.5/10 | OK skeleton, ale słabe hover states |
| SupervisorDashboard | 5.5/10 | Najsłabszy — px zamiast rem, brak icon wraps |

## Wzorce do naśladowania
- **Softphone** dla przycisków akcji (scale hover, colored shadow)
- **Admin Dashboard** dla kart z metrykami (icon-wrap + label + value)
- **IVR Editor** dla canvas/diagram UI (dot-grid, color-coded nodes)

## Komponenty wymagające pilnego refaktoru
1. SupervisorDashboard — mieszanie px/rem, brak icon wraps, magic numbers
2. Login — brak entrance animation, płaskie tło, słaby brand
3. Wszystkie tabele — row hover bez depth

## How to apply
Przy pracy nad danym komponentem sprawdź jego pozycję w rankingu. Komponenty z oceną <6 wymagają więcej uwagi premium UI.
