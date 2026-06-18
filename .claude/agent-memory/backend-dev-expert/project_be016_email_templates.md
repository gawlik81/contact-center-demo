---
name: BE-016 Email Templates CRUD API
description: Implementacja szablonów odpowiedzi email z renderowaniem Mustache
type: project
---

BE-016 zaimplementowany: CRUD API dla szablonów email z silnikiem Mustache.

**Why:** Agenci potrzebują wielokrotnego użytku szablonów odpowiedzi email z dynamicznymi zmiennymi.

**How to apply:** Nowe pliki w pakiecie `domain/email` i `api/email`:
- `EmailTemplate` (encja JPA), `EmailTemplateRepository`, `EmailTemplateService`, `EmailTemplateController`
- `MustacheTemplateEngine` – silnik renderowania z wykrywaniem brakujących zmiennych
- `TemplateRenderException` – HTTP 422 z listą `missingVariables`
- Migracja V028 (email_template table + RLS)
- Mustache dependency: `com.github.spullara.mustache.java:compiler:0.9.14`

**Integracja z EmailSendService:** `sendReplyWithTemplate(tenantId, originalMessageId, templateId, variables, agentId)` – renderuje szablon i wywołuje istniejące `sendReply()`. EmailSendService wymaga teraz `EmailTemplateService` jako zależności.

**Walidacja zmiennych:** `variables` w encji to lista wymaganych nazw (JSONB). Przy renderowaniu serwis sprawdza czy wszystkie są w mapie kontekstu. MustacheTemplateEngine ma osobną metodę `findMissingVariables()` do analizy regex szablonu (niezależna od deklaracji w encji).

**Dostęp API:** `@PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")` na poziomie kontrolera (SecurityConfig `anyRequest().authenticated()` wystarczy – `@PreAuthorize` daje granularność).

**Bugfix podglądu (preview) – wsparcie customerId (2026-06-18):** Endpoint `POST /api/email-templates/{id}/preview` obsługiwał tylko `contactId`. Dla maila ad hoc (modal frontend) kontakt OUTBOUND jeszcze nie istnieje w momencie podglądu — frontend ma tylko `customerId`. Dodano:
- `PreviewRequest.customerId` (UUID, nullable, obok `variables`/`contactId`)
- `TemplateVariableResolver.resolveForContext(UUID contactId, UUID customerId, UUID agentId)` – nowy overload; stary 2-argumentowy delegowany do nowego z `customerId=null`
- `TemplateVariableResolverImpl` – metoda pomocnicza `resolveCustomerVarsByCustomerId(customerId, tenantId, vars)` używana zarówno wprost (gdy podano `customerId`) jak i poprzez ścieżkę `contactId→Contact.getCustomerId()→Customer`
- Priorytet w `EmailTemplateController.preview()`: `customerId` > `contactId` > fallback `PredefinedTemplateVariable` (przykładowe wartości)
- `EmailSendServiceImpl` (odpowiedzi na e-mail kontaktu) nadal używa starego 2-argumentowego `resolveForContext(contactId, agentId)` – bez zmian

Testy: nowy plik `TemplateVariableResolverImplTest` (7 testów: priorytet customerId>contactId, fallback do contactId, puste wartości gdy brak danych/klient nieznaleziony, zmienne agenta niezależne, delegacja starego overloadu).
