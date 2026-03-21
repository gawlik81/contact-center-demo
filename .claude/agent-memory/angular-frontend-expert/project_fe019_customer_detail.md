---
name: Customer detail view and contact history (FE-019)
description: CustomerDetailComponent – widok szczegółowy klienta z historią kontaktów dla roli Supervisor
type: project
---

CustomerDetailComponent zaimplementowany w `frontend/src/app/features/supervisor/pages/customers/customer-detail/`.
Route: `/supervisor/customers/:id` (dodana do `supervisor.routes.ts`).

**Kluczowe decyzje:**
- Komponent czyta `customerId` z `ActivatedRoute.paramMap` przez `switchMap` – obsługuje zmianę parametru bez re-subscribe
- Stan ładowania rozdzielony na dwa sygnały: `loadState` (customer) i `contactsLoadState` (historia)
- HTTP 404 przy ładowaniu klienta → stan `not-found` z komunikatem i przyciskiem powrotu
- `CustomerService` rozszerzony o `getCustomerContacts(params: ContactListParams)` → `GET /api/contacts?customerId=&page=&size=`
- `ContactResponse` i `SetDispositionRequest` przeniesione do `core/models/contact.model.ts` (po CR-FE-019); `features/agent/models/contact.model.ts` jest teraz cienkim re-eksportem
- `ContactListParams` – nowy interfejs w pliku customer.service.ts
- `getCustomerContacts` w `CustomerService` ma guard `Math.min(params.size, 100)` chroniący przed nieograniczonym size
- Paginacja historii kontaktów: sygnały `contactsCurrentPage`, `contactsTotalPages` z dedykowanymi metodami `onContactsPrevPage/Next`
- `computed()` dla `customerName`, `hasCustomFields`, `contactsFirstIndex`, `contactsLastIndex`
- Brak redundantnego sygnału `customerId` – zamiast niego `customer()?.customerId` (po CR-FE-019)
- `contactsLoadState` ma stan `'error'` z przyciskiem "Spróbuj ponownie" (po CR-FE-019)
- Route `customers/:id` w `supervisor.routes.ts` ma explicite `canActivate: [roleGuard]` + `data.roles: ['SUPERVISOR', 'ADMIN']`

**Sekcje widoku:**
1. Nagłówek: imię+nazwisko (lub phone/email/id jako fallback), data od, przyciski Wróć/Edytuj
2. Dane kontaktowe: phone[] + email[] jako `<ul>`, source, updatedAt
3. Zgoda RODO: gdpr_consent badges (Tak/Nie), data zgody, źródło zgody, marketing_consent
4. Custom fields (sekcja ukryta gdy puste): `KeyValuePipe` na `Record<string, unknown>`
5. Historia kontaktów: tabela paginowana, channel/status badge CSS

**Style:** czyste SCSS bez Angular Material (zgodne z resztą supervisor UI), badge klasy CSS `channel-badge--voice/email/chat/social`, `status-badge--active/ended/queued/wrap_up`.

**Why:** FE-019 wymagało widoku detalu klienta z historią kontaktów dla supervisora.
**How to apply:** Przy rozbudowie – komponent jest w customers/customer-detail, CustomerService ma metodę getCustomerContacts.
