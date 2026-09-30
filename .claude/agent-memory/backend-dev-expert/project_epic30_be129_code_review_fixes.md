---
name: project-epic30-be129-code-review-fixes
description: EPIC-30 BE-129 code review (3/5) poprawki — TOCTOU guard FOR UPDATE bez SKIP LOCKED, GdprControllerTest MockMvc realnie testujący @PreAuthorize/409, usunięcie tautologicznego assertSameTenant
metadata:
  type: project
---

Code review BE-129 (2026-09-24, `CR-BACKEND.md`) dało 3/5 z dwoma majorami. Poprawki w tej samej
gałęzi `feature/epic-30-message-retention`, zobacz [[project-epic30-be129-gdpr-java-integration]]
dla oryginalnej implementacji.

**BE129-01 (major, TOCTOU w guardzie „rekordów w toku"):** zwykły `SELECT` bez blokady w
`GdprRepository#SQL_HAS_IN_PROGRESS_RECORDS` NIE eliminował wyścigu pod READ COMMITTED — dialer
mógł przeklaimować rekord MIĘDZY guardem a `anonymize_customer` w tej samej transakcji. Fix:
`FOR UPDATE OF cc`/`FOR UPDATE OF sb` na CTE joinujących do `fn_customer_subject_ids`, **celowo BEZ
filtra po statusie w WHERE blokady** — trzeba zablokować WSZYSTKIE wiersze zbioru podmiotu (nie tylko
już-DIALING), bo filtrowanie po statusie PRZED zablokowaniem daje TEN SAM wyścig, tylko przesunięty
o jeden krok (EvalPlanQual po odblokowaniu wyklucza wiersz, którego status się zmienił, więc
zniknąłby z zablokowanego zbioru). Status sprawdzany DOPIERO w zewnętrznym `EXISTS` po uzyskaniu
blokady.

**Empirycznie zweryfikowane (scratch Postgres 16 + potem realny Testcontainers z pełnym Flyway):**
1. `FOR UPDATE OF <alias>` DZIAŁA składniowo wewnątrz `EXISTS(...)` i wewnątrz zwykłego (nie tylko
   MATERIALIZED) CTE, nawet gdy CTE joinuje z funkcją zwracającą zbiór (`fn_customer_subject_ids`) —
   ograniczenie „FOR UPDATE nie może być użyte z funkcją zwracającą zbiór" dotyczy WYŁĄCZNIE funkcji
   w liście SELECT, nie w FROM/JOIN. Nie zgaduj tego z pamięci — sprawdzaj przez `EXPLAIN`.
2. `ProgressiveDialerServiceImpl#fetchNextPendingContact` (`FOR UPDATE SKIP LOCKED`) POMIJA wiersz
   zablokowany przez guard (nie czeka) — potwierdzone realnym testem współbieżności (dwie sesje JDBC).
3. `ScheduledCallbackRepository#updateStatusIfPending` (zwykły `UPDATE ... WHERE status='PENDING'`,
   BEZ własnego `FOR UPDATE`) CZEKA na commit guardu, potem trafia na już zmieniony status i
   aktualizuje 0 wierszy — plain UPDATE w Postgresie i tak próbuje zablokować docelowy wiersz, więc
   respektuje cudzą `FOR UPDATE` nawet bez własnej.
4. Odtworzono TEŻ oryginalny scenariusz z recenzji w drugą stronę: dialer klaimuje PIERWSZY (trzyma
   blokadę przez `initiateDialForAgent`, `@Transactional`, obejmujące telefonię!) — guard URUCHOMIONY
   W TRAKCIE poprawnie CZEKA, a po commit dialera widzi świeży `DIALING` i zwraca „w toku".

**Ryzyko rezydualne (udokumentowane w kodzie i tickecie, NIE naprawione — poza zakresem):** skoro
`initiateDialForAgent` jest `@Transactional` i OBEJMUJE wywołanie telefonii (zewnętrzne I/O w
transakcji — osobny, pre-existing anti-pattern), żądanie anonimizacji może CZEKAĆ na zakończenie
całej inicjacji połączenia, jeśli dialer akurat trzyma blokadę na jednym z rekordów klienta. To
świadomy koszt poprawności (blokować, nie pomijać) — nie do usunięcia bez zmiany granic transakcji
w `ProgressiveDialerServiceImpl` (plik poza zakresem edycji tego zlecenia).

**BE129-02 (major, brak testu Spring/MockMvc):** ODKRYCIE — `CR-BACKEND.md` twierdził, że
`RetentionControllerTest`/`EmailAttachmentControllerTest` „MA testy na poziomie Springa (MockMvc)"
na podstawie grep po słowie „MockMvc". To FAŁSZYWY POZYTYW: oba pliki wspominają „MockMvc" WYŁĄCZNIE
w Javadoc tłumaczącym, że go NIE UŻYWAJĄ (bezpośrednie wywołanie metody kontrolera). Zanim
zaufasz cytatowi z code review o „istniejącym wzorcu testowym" — zweryfikuj samą treść pliku, nie
tylko fakt dopasowania grepa. Prawdziwy wzorzec MockMvc w tym repo: `CampaignImportControllerTest`/
`CustomerImportControllerTest` (`@WebMvcTest` + lokalny `MinimalBootConfig` z
`@SpringBootConfiguration @EnableAutoConfiguration @Import(Controller.class)` — bez realnego
`ContactCenterApplication`, bo ten wymaga `EntityManagerFactory`) — ale te dwa też używają
`@AutoConfigureMockMvc(addFilters = false)` i NIE testują `@PreAuthorize`.

**Nowy wzorzec (ten commit, `GdprControllerTest`):** żeby realnie egzekwować `@PreAuthorize` mimo
wyłączonego łańcucha filtrów servletowych (`addFilters = false`, unika ciężkich zależności
`JwtAuthFilter`/`JwtService`), dodaj `@EnableMethodSecurity(prePostEnabled = true)` na
`MinimalBootConfig` — to OSOBNY mechanizm AOP (`MethodSecurityInterceptor`) czytający WYŁĄCZNIE
`SecurityContextHolder`, niezależny od `FilterChainProxy`. `@WithMockUser(roles = "...")`
(spring-security-test, już w `pom.xml`) ustawia `SecurityContextHolder` przed testem — działa mimo
braku `JwtAuthFilter`. `GlobalExceptionHandler` leży w pakiecie NADRZĘDNYM (`api`, nie `api.customer`)
względem kontrolera — trzeba go DOPISAĆ jawnie do `@Import({Controller.class,
GlobalExceptionHandler.class})`, bo skanowanie od `MinimalBootConfig` idzie tylko w dół własnego
pakietu. Pułapka: test „brak Authentication w ogóle" (nie `@WithMockUser`, zupełnie pusty
`SecurityContextHolder`) NIE daje 403 w tym setupie — `PreAuthorizeAuthorizationManager` rzuca coś,
co ląduje w `handleGenericException` (500), bo brakuje `AnonymousAuthenticationFilter`
(wyłączonego razem z resztą łańcucha) ustawiającego domyślne „anonymousUser"; w produkcji ZAWSZE
jest jakaś `Authentication` (realny JWT albo anonymous), więc ten scenariusz nie jest realny — nie
testuj go w tym harnessie, usuń zamiast obchodzić.

**BE129-03 (minor, tautologiczne assertSameTenant):** USUNIĘTE (nie zostawione z komentarzem) z
`GdprRepository#anonymize`/`#exportCustomerData` — `assertSameTenant(tenantId, customerId)`
porównywało `TenantContext.getTenantId()` z wartością pochodzącą z TEGO SAMEGO
`TenantContext.getTenantId()` u wołającego (`GdprServiceImpl`), więc nigdy nie mogło rzucić.
Zostawiony JEDNOZDANIOWY komentarz wyjaśniający dlaczego go nie ma (żeby przyszły czytelnik nie
pomyślał, że to przeoczenie względem konwencji `CLAUDE.md`).

**How to apply:** przy przyszłych guardach opartych o `FOR UPDATE` w tym repo (jakikolwiek kod
robiący „sprawdź stan → zdecyduj → zmutuj" w jednej transakcji, gdzie inny proces może przeklaimować
wiersz między krokami) — blokuj SZERZEJ niż filtr decyzji, status/warunek sprawdzaj PO
zablokowaniu, nie przed. Powiązane: [[feedback-jpa-real-db-integration-test-harness]],
[[project-be024-progressive-dialer]] (FOR UPDATE SKIP LOCKED, źródło wzorca dialera),
[[project-be038-scheduled-callback-executor]] (updateStatusIfPending, źródło wzorca executora).
