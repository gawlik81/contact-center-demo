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

**Rozszerzenie o externalId i customFields klienta (2026-07-05, branch `customer-refactor`):** Dodano dwa nowe źródła danych klienta do zmiennych szablonów:
- `PredefinedTemplateVariable.CUSTOMER_EXTERNAL_ID` ("customerExternalId") – zwykła stała enuma, prosty string z `Customer.getExternalId()`.
- `"customerCustomFields"` – celowo NIE jest stałą `PredefinedTemplateVariable` (ta reprezentuje tylko proste stringi ze scalarnym `exampleValue`). To surowa `Map<String,Object>` z `Customer.getCustomFields()` wstawiana wprost do `vars`/`variables`, dostępna w Mustache przez natywną notację kropkową JMustache (`{{customerCustomFields.vip}}`) – JMustache **natywnie** rozwiązuje zagnieżdżony dostęp do obiektów Map w kontekście, bez żadnej dodatkowej logiki w silniku.
- **Pułapka walidacji:** `EmailTemplateServiceImpl.render()` sprawdzał obecność zadeklarowanych zmiennych (`template.getVariables()`) przez dokładne dopasowanie klucza. Autor szablonu piszący `{{customerCustomFields.vip}}` deklaruje literalnie `"customerCustomFields.vip"`, którego nie ma jako klucza w mapie (tam jest tylko `"customerCustomFields"`) → fałszywy `TemplateRenderException`, mimo że renderowanie faktycznie by zadziałało. Fix: nowa prywatna metoda `isVariablePresent(varName, variables)` – dla nazw z kropką sprawdza też root (segment przed pierwszą kropką) w mapie kontekstu i w `PredefinedTemplateVariable.BY_KEY`.
- Ten sam problem (regex, nie rozumie kropki) opisany w Javadoc `MustacheTemplateEngine.extractVariableNames()`/`findMissingVariables()` – NIE zmieniano tej metody (osobny, niezależny mechanizm walidacji nieużywany przez `EmailTemplateServiceImpl.render()`).
- `putEmptyCustomerVars()` i `EmailTemplateController.preview()` (gałąź "brak kontekstu") również zaktualizowane o puste/przykładowe wartości dla obu nowych pól.
- Testy rozszerzone (nie nowe pliki): `TemplateVariableResolverImplTest` (+3 testy: resolve z encji, null→pusta mapa, empty-context), `EmailTemplateServiceTest` (+2 testy: root obecny→renderuje mimo braku dokładnego klucza, root brakuje→nadal 422), `MustacheTemplateEngineTest` (+2 testy regresyjne potwierdzające natywne wsparcie JMustache dla `{{obj.klucz}}`). Brak `EmailTemplateControllerTest` w repo – pominięto zgodnie z zasadą "nie twórz nowych plików testowych".
- Pełny `mvn test -pl app`: 1457 testów, 0 failures/errors, BUILD SUCCESS.
