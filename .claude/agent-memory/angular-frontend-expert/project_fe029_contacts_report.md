---
name: Contacts report page (FE-029)
description: ContactsReportComponent – strona Raporty > Kontakty dla supervisora z 7 filtrami, tabelą, eksportem CSV i integracją ContactDetailModal
type: project
---

Zrealizowane 2026-04-08.

## Pliki

- `frontend/src/app/features/supervisor/pages/contacts-report/contacts-report.component.ts` – komponent
- `frontend/src/app/features/supervisor/pages/contacts-report/contacts-report.component.html`
- `frontend/src/app/features/supervisor/pages/contacts-report/contacts-report.component.scss`
- `frontend/src/app/features/agent/services/contact.service.ts` – rozszerzony o `getContacts()` + `ContactFilterParams`

## Architektura

- Route: `/supervisor/reports/contacts`, `canActivate: [roleGuard]`, roles SUPERVISOR/ADMIN
- Sidenav: element "Raporty" stał się sekcją rozwijalną z dziećmi "Historyczne" (istniejący /supervisor/reports) i "Kontakty" (nowy /supervisor/reports/contacts)
- ContactService.getContacts(filters, page, size): GET /api/contacts z HttpParams
- ContactFilterParams: dateFrom, dateTo, channel, status, queueId, campaignId, remoteAddress, durationMin, durationMax

## Wzorce

- 7 filtrów: date inputs para, 3 selecty (channel/status/queue), text input z debounce 400ms, 2 number inputs z debounce 400ms
- URL query params sync przez `router.navigate([],{replaceUrl:true})` tak jak w ReportsComponent
- Skeleton: 10 wierszy (`skeletonRows = [1..10]`)
- Tabela: 9 kolumn – datetime (DD.MM.YYYY HH:MM), channel badge, direction (Przych./Wych. arrow), remoteAddress (monospace), queue (lookup by queueId → queue.name lub skrócony UUID), duration (MM:SS lub —), status badge, dispositionCode, icon button oko
- Badge klasy: `channel-badge--{voice|email|chat|social}`, `status-badge--{completed|abandoned|failed|active|queued}`, `direction-label--{inbound|outbound}`
- Eksport CSV: client-side z aktualnej strony, BOM UTF-8, `contacts-YYYY-MM-DD.csv`
- Modal: `selectedContactId = signal<string|null>(null)`, ContactDetailModalComponent w `imports[]`
- QueueService.getQueues(0,200) do wypełnienia dropdownu kolejek

**Why:** Supervisor potrzebuje widoku historii kontaktów z filtrami i możliwością otwierania szczegółów – uzupełnienie FE-022 (raporty per agent) o widok per kontakt.

**How to apply:** Przy kolejnych stronach raportowych – używaj tego komponentu jako wzorca (filtry+URL sync+tabela+paginacja+export+modal).
