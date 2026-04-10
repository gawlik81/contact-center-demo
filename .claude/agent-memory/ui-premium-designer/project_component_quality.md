---
name: Component Quality Rankings
description: Ranking jakości komponentów — aktualizacja 2026-04-10 po przeglądzie panelu agenta
type: project
---

# Ranking jakości komponentów (ostatnia aktualizacja: 2026-04-10)

**Why:** Analiza pozwala priorytetyzować pracę i wskazać wzorce do naśladowania.

## Ranking (od najlepszego)

| Komponent | Ocena | Uwagi |
|-----------|-------|-------|
| IVR Editor | 7.5/10 | Dot-grid canvas, color-coded nodes, dash-flow animation |
| Admin Dashboard | 7/10 | Skeleton loaders, icon wraps, KPI structure |
| Customer Panel | 4.5/10 | Dobry skeleton, error state bez retry, cp__cta-btn złe kolory |
| Agent Desktop | 4/10 | Dobre taby, ale brak prefers-reduced-motion, bug kontrast danger |
| Disposition Panel | 4/10 | Dobry ACW, zły easing, zły backdrop, brak reduced-motion |
| Email Contact | 6.5/10 | Split layout, custom scrollbar, focus-within |
| Sidenav | 6.5/10 | Responsive, will-change, focus rings |
| TopNavbar | 6/10 | Dobry logout hover, ale słaby brand |
| Manual Campaign Panel | 3.5/10 | Btn-call za mały (28px), brak retry, nieregularny padding |
| Softphone | 3/10 | WCAG kontrast naruszenia, cały komponent w px, brak reduced-motion |
| Login | 5/10 | Płaskie tło, brak entrance animation |
| TenantList/QueueList | 5.5/10 | OK skeleton, ale słabe hover states |
| SupervisorDashboard | 5.5/10 | Mieszanie px/rem, brak icon wraps, magic numbers |

## Kluczowy problem globalny — agent panel (2026-04-10)
Żaden z 5 komponentów panelu agenta (agent-desktop, softphone, customer-panel, disposition-panel, manual-campaign-panel)
NIE MA bloku `@media (prefers-reduced-motion: reduce)`. To naruszenie WCAG 2.1 kryterium 2.3.3.

## Softphone — korekta oceny (był 8/10, teraz 3/10)
Po szczegółowym przeglądzie SCSS: softphone używa hardkodowanych px zamiast rem,
Material Design 2 szarości (#212121, #616161) niezgodnych z tokenami projektu,
3 naruszenia WCAG AA kontrastu i brak prefers-reduced-motion przy 4 animacjach.

## Wzorce do naśladowania
- **Customer Panel** dla skeleton loader i struktury stanów (loading/empty/error/known)
- **Admin Dashboard** dla kart z metrykami (icon-wrap + label + value)
- **IVR Editor** dla canvas/diagram UI (dot-grid, color-coded nodes)
- **Disposition Panel** dla formularzy z ACW timerem (mimo problemów easing)

## Komponenty wymagające pilnego refaktoru (priorytet 1)
1. Softphone — px→rem, tokeny kolorów, 3x WCAG AA, prefers-reduced-motion
2. Manual Campaign Panel — btn-call rozmiar, retry, padding
3. Customer Panel — retry w error state, cta-btn kolor

## How to apply
Przy pracy nad softphone — nie kopiuj jego wzorców stylistycznych (błędy). Wzoruj się na disposition-panel dla struktury SCSS.
