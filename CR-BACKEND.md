# CR-BACKEND.md – Code Review Backend

---

## Review: EPIC-21 — Retry i callback w kampaniach wychodzących — 2026-05-08

**Branch:** EPIC-21  
**Reviewer:** senior-code-reviewer agent  
**Zakres:** BE-062 … BE-066 + CampaignWindowActivator

---

### CRITICAL

#### [ScheduledCallbackExecutor.java:181-241] campaign_contact utknie na DIALING po błędzie telefonii

`markAsDialingForCallback()` jest wywoływany PRZED `telephonyAdapter.initiateCall()`. Gdy `initiateCall()` rzuci `TelephonyException`, `campaign_contact` pozostaje na zawsze w statusie `DIALING` — rekord nie wróci do kolejki dialera.

**Naprawa** — rollback w bloku `catch`:

```java
} catch (TelephonyAdapter.TelephonyException e) {
    callbackRepository.updateStatus(callback.getCallbackId(), "FAILED", callback.getTenantId());
    if (isCampaignCallback) {
        campaignContactRepository.updateStatus(
            callback.getCampaignContactRecordId(), callback.getCampaignId(),
            callback.getTenantId(), "CALLBACK", callback.getScheduledAt(), "CALLBACK");
    }
}
```

---

#### [DialerCallbackHandler.java:149-155] Permanentna blokada agenta przy wyjątku w ścieżce NO_ANSWER

Gdy `handleNoAnswer(...)` rzuci wyjątek, `cleanupRedisKeys(callSid, agentId)` NIE jest wywoływane. Klucz `dialer:agent:{agentId}` blokuje agenta przez TTL=60s.

**Naprawa** — przenieś `cleanupRedisKeys` do `finally`:

```java
} finally {
    cleanupRedisKeys(callSid, agentId);
    TenantContext.clear();
}
```

---

### HIGH

#### [CampaignWindowActivator.java:177] DateTimeParseException nie jest obsługiwany w isPastEndDate

Błędny `end_date` (np. `"2026-13-01"`) przerywa iterację wszystkich kampanii tenanta. Pozostałe kampanie RUNNING nie są sprawdzane.

**Naprawa** — wrap w `try/catch (DateTimeParseException e)` z logiem WARN i `return false`.

---

#### [CampaignContactRepository.java:308-309] Podwójne wywołanie set_tenant_context w markAsDialingForCallback

Linia 308: `setTenantContextInDb(tenantId)`, linia 309: ten sam stored procedure przez `jdbcTemplate`. Zbędny podwójny round-trip do DB.

**Naprawa** — usuń linię 309.

---

### MEDIUM

#### [DialerCallbackHandler.java:144] Twilio outcome "failed" traktowany jako COMPLETED

`"failed"` = błąd sieci Twilio, semantycznie bliżej `NOT_REACHED`. Zaburza raportowanie. Jeśli decyzja biznesowa — wymaga komentarza w kodzie.

---

#### [ScheduledCallbackExecutor.java:218] Hardkodowany TTL 1800 zamiast stałej

`ProgressiveDialerService.CALL_STATE_TTL_SECONDS = 1800` nie jest reużywany. Wyodrębnij do `DialerConstants`.

---

#### [CampaignWindowActivator.java:135-152] Zamykanie PAUSED kampanii po end_date — nieudokumentowane

Kampanie PAUSED są automatycznie zamykane jako COMPLETED po minięciu `end_date`. Może zaskoczyć użytkownika. Udokumentować lub dodać property konfiguracyjny.

---

#### [DialerCallbackHandler.java:258] Nieaktualny Javadoc po BE-064

Javadoc mówi `COMPLETED`, po zmianie status to `CALLBACK`.

---

#### [TwilioTelephonyAdapter.java:626-629] hangupCall() zawsze publikuje outcome "completed"

Nawet gdy połączenie jest w fazie `ringing`. Może powodować konflikt z webhokiem Twilio.

---

### LOW

#### [DialerCallbackHandlerTest.java:57] @MockitoSettings LENIENT na całej klasie — powinno być STRICT_STUBS

#### [ProgressiveDialerServiceTest.java:886] Test campaignOutOfSchedule niestabilny w 00:00–00:01 — użyj Clock mock

#### [DialerCallbackHandler.java:474] setTenantContextInJdbc jako one-liner — ujednolicić formatowanie

---

### Pozytywne obserwacje

- Wzorzec dwóch kluczy Redis dla callback attempt — elegancki
- Usunięcie hardkodowanego guard 4h na rzecz `next_attempt_at <= NOW()`
- Test refleksji `isCalledTooRecently_methodDoesNotExist` — wartościowy test regresji
- `markAsDialingForCallback` nie inkrementuje `attempt_count` — poprawna semantyka
- `NOT_REACHED` vs `FAILED` — lepsza semantyka statusów kampanijnych
- Testy z helperami `assertStatusParamUsed/NeverUsed` — dobra jakość

---

### Pliki wymagające poprawki przed merge

| Priorytet | Plik | Problem |
|-----------|------|---------|
| CRITICAL | `ScheduledCallbackExecutor.java` | Rollback DIALING→CALLBACK w catch |
| CRITICAL | `DialerCallbackHandler.java` | cleanupRedisKeys w finally |
| HIGH | `CampaignWindowActivator.java` | Guard DateTimeParseException |
| HIGH | `CampaignContactRepository.java` | Usunięcie zduplikowanego set_tenant_context |

---

## Review: BE-017 – OAuth flow i zarządzanie tokenami social media — 2026-04-16

Przejrzane pliki:
- `domain/model/SocialPlatform.java`
- `domain/model/SocialIntegration.java`
- `domain/repository/SocialIntegrationRepository.java`
- `domain/service/SocialTokenEncryptionService.java`
- `domain/service/SocialIntegrationService.java`
- `api/social/SocialOAuthController.java`
- `api/social/dto/SocialIntegrationDto.java`
- `api/social/dto/SocialIntegrationListResponse.java`
- `api/social/dto/OAuthInitiateResponse.java`
- `security/SecurityConfig.java` (fragment)
- `security/TenantFilter.java` (fragment)
- `resources/application.yml` (fragment)
- `db/migration/V010__create_email_social.sql` (schema)
- `db/migration/V012__row_level_security.sql` (RLS)
- `test/.../SocialTokenEncryptionServiceTest.java`

---

### CRITICAL

**[SocialOAuthController.java:81-86] Parametr `state` generowany, ale nigdy nie weryfikowany w callbacku — OAuth CSRF protection jest fikcyjna.**

`initiateOAuth()` generuje `state = UUID.randomUUID()` i zwraca go do klienta, ale ten `state` nie jest nigdzie zapamiętany (Redis, sesja, baza). Endpoint `oauthCallback()` (linia 117) przyjmuje `state` jako parametr, loguje go, lecz go nie waliduje — nie porównuje z wartością zapisaną przy inicjacji.

Skutek: dowolny atakujący może skonstruować fałszywy URL callbacku z dowolnym `code` i poprawnym `platform`, a serwer wykona wymianę tokenu i zapisze integrację dla tenanta ofiary. To pełny CSRF na flow OAuth 2.0.

Wymagana naprawa: przy wywołaniu `initiateOAuth()` zapisać `state` w Redis z TTL np. 10 minut pod kluczem `oauth:state:{tenantId}:{state}`. W callbacku sprawdzić istnienie i jednokrotność tego klucza (natychmiast usunąć po weryfikacji — prevent replay). Callback bez JWT nie ma TenantContext, więc `state` musi zawierać `tenantId` (np. `{tenantId}:{randomUUID}`) lub być przechowywany per-sesja po stronie frontendu z przekazaniem przez fragment URL.

---

**[SocialIntegrationService.java:317-318] Token dostępu w plaintext w URL żądania HTTP do Graph API — wyciek tokenu do logów i infrastruktury.**

Metoda `revokeTokenAtProvider()` buduje URL:
```
String url = String.format("%s/%s/permissions?access_token=%s", GRAPH_API_BASE, integration.getPageId(), token);
```
Token w query stringu URL trafia do:
1. Logów HTTP klienta (JDK HttpClient domyślnie nie loguje, ale jest to niebezpieczna praktyka)
2. Potencjalnie do logów load balancera, CDN, reverse proxy — URL z tokenem w query string jest w access logu nginx/haproxy
3. Serverowych logów TLS inspection w środowiskach korporacyjnych

Wymóg Graph API dla revoke to DELETE z tokenem w nagłówku `Authorization: Bearer {token}` lub jako parametr POST body, nie w URL. Poprawna implementacja:
```java
HttpRequest request = HttpRequest.newBuilder()
    .uri(URI.create(String.format("%s/%s/permissions", GRAPH_API_BASE, integration.getPageId())))
    .DELETE()
    .header("Authorization", "Bearer " + token)
    .build();
```

---

**[SocialIntegrationService.java:325] Blokujące wywołanie HTTP `httpClient.send()` wewnątrz metody `@Transactional` — ryzyko deadlocku puli połączeń.**

`deleteIntegration()` jest oznaczona `@Transactional` (linia 155). Wywołuje `revokeTokenAtProvider()` (linia 167), która wykonuje synchroniczne (`send()`, nie `sendAsync()`) wywołanie zewnętrznego Graph API. Transakcja bazy danych trzyma blokadę przez cały czas oczekiwania na odpowiedź z zewnętrznego API (domyślny timeout HttpClient = brak). Przy dużym ruchu lub niedostępności FB API pula połączeń HikariCP ulega wyczerpaniu.

Naprawa: wykonać `revokeTokenAtProvider()` POZA transakcją — wydzielić metodę z `@Transactional(propagation = NEVER)` lub wykonać najpierw commit (pobierz i zapisz token przed transakcją), a revoke wykonaj asynchronicznie po commicie DB.

---

**[SocialIntegrationService.java:325] `httpClient.send()` łapie `InterruptedException` przez generyczne `catch (Exception e)` — wątek schedulera może tracić interrupt flag.**

W `revokeTokenAtProvider()` linia 334: `catch (Exception e)`. Metoda `HttpClient.send()` deklaruje `throws InterruptedException`. Połknięcie `InterruptedException` bez przywrócenia flagi przerywa mechanizm kooperatywnego zatrzymywania wątku. W wątku `@Scheduled` schedulera Springa może to blokować graceful shutdown.

Naprawa:
```java
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    log.warn("[SocialIntegration] Revoke przerwany: {}", e.getMessage());
} catch (Exception e) {
    log.warn(...);
}
```

---

**[V010__create_email_social.sql] Tabela `social_integration` nie ma kolumny `is_deleted` — brak soft delete wymaganego przez konwencje projektu.**

Schemat tabeli (linie 215-258) nie definiuje `is_deleted BOOLEAN NOT NULL DEFAULT FALSE`. Wszystkie pozostałe encje w projekcie stosują soft delete. Implementacja wykonuje `em.remove()` (twarde usunięcie), co:
1. Narusza konwencję projektu
2. Usuwa ślad audytowy w DB (jest tylko AuditLog w osobnej tabeli, ale rekord integration_id nie jest archiwizowany)
3. Blokuje FK z `social_message.integration_id` — aktualnie ON DELETE SET NULL, ale po hard delete historyczne wiadomości tracą powiązanie z integracją

Jeśli decyzja o hard delete dla tej tabeli jest świadoma (tokeny nie powinny zostawać w DB po revoke), to należy to udokumentować jako jawny wyjątek od konwencji i upewnić się, że `social_message.integration_id` ON DELETE SET NULL jest właściwym zachowaniem dla zachowania historii wiadomości.

---

**[SocialIntegrationService.java:233-295] `refreshToken()` wywołuje `TenantContext.setTenantId()` bez `snapshot()/restore()` — wzorzec niezgodny z wymaganiami projektu dla async/scheduler.**

Metoda używa bezpośrednio `TenantContext.setTenantId(tenantId)` zamiast wymaganego wzorca `snapshot()/restore()`. Wątek schedulera Spring może być współdzielony (pula `TaskScheduler`). Chociaż `finally { TenantContext.clear() }` czyści kontekst, bezpośrednie `setTenantId` zamiast restore z snapshota jest niezgodne z konwencją projektu dla przekraczania granic wątków.

Poważniejszy problem: `TenantContext.setTenantId()` ustawia tylko `tenantId`, ale nie `tenantName`. Jeśli serwisy downstream (`auditLogService.publishAuditEvent()`) używają `TenantContext.getTenantName()` wewnętrznie, dostają `null`.

Wymagana naprawa zgodna z CLAUDE.md: stworzyć `TenantContext.Snapshot` przed pętlą (lub per-integracja), użyć `restore()` i `clear()` w finally.

---

### WARNING

**[SocialIntegrationService.java:350-362] `exchangeForLongLivedToken()` jest stubem zwracającym ten sam token — scheduler odświeżający tokeny nie działa produkcyjnie, ale działa jak gdyby działał (błędnie zapisuje 60-dniową datę wygaśnięcia).**

Linia 362: `return shortLivedToken;`. Scheduler w `refreshToken()` (linia 249) zapisuje `Instant.now().plus(60, ChronoUnit.DAYS)` jako nową datę wygaśnięcia, mimo że token nie został faktycznie wymieniony. Oznacza to, że wygasłe tokeny będą udawać, że są świeże przez kolejne 60 dni. Integracja nie będzie oznaczona jako `EXPIRED_TOKEN` dopóki rzeczywista operacja API (np. wysłanie wiadomości) nie zwróci błędu auth.

Stub powinien rzucać `UnsupportedOperationException` lub `NotImplementedException` zamiast udawać sukces, albo być wyraźnie wyłączony conditionally przez feature flag.

---

**[SocialOAuthController.java:239-243] `exchangeCodeForToken()` jest stubem zwracającym `code` jako token — w środowiskach innych niż dev/test stub zapisze nieprawidłowy token do bazy.**

Linia 243: `return code;`. OAuth authorization code jest jednorazowy i krótkotrwały (typowo 10 minut). Stub zwraca go jako access token. Jeśli ta gałąź kodu zostanie wdrożona bez implementacji produkcyjnej, wywołania API z tym "tokenem" będą się natychmiast kończyć błędem 400 od Graph API, ale token zostanie zaszyfrowany i zapisany w DB.

Ta sama uwaga co powyżej: stub powinien rzucać `NotImplementedException` zamiast cicho zwracać nieprawidłowe dane.

---

**[SocialIntegrationRepository.java:94-101] `findAllExpiringBefore()` pomija `setTenantContextInDb()` — zapytanie cross-tenant bez RLS.**

To jest intentional (komentarz w kodzie mówi "BYPASSES RLS"), ale brakuje zabezpieczenia przed przypadkowym wywołaniem tej metody spoza kontekstu schedulera. Metoda jest `public` i może być wywołana z dowolnego serwisu. Brak jest żadnego mechanizmu (np. dedykowana adnotacja, package-private visibility, lub sprawdzenie że `TenantContext` jest pusty) wymuszającego, że ta metoda jest wyłącznie dla użycia przez scheduler systemowy.

Rekomendacja: zmienić widoczność na package-private lub dodać asercję `Assert.isNull(TenantContext.getTenantId(), "findAllExpiringBefore() nie może być wywołane w kontekście tenanta")`.

---

**[SocialOAuthController.java:112-168] Callback OAuth jest publiczny i zwraca `SocialIntegrationDto` z `pageId` — brak TenantContext w momencie zapisu.**

Endpoint `/api/oauth/{platform}/callback` jest publiczny (bez JWT). Wywołuje `integrationService.saveIntegration()`, które wewnętrznie wywołuje `TenantContext.getTenantId()` (linia 85 w serwisie). Ponieważ callback nie ma JWT, `TenantContext` nie jest ustawiony przez `JwtAuthFilter`/`TenantFilter` — `getTenantId()` zwróci `null`.

Skutek: `repository.save(integration)` wywoła `assertSameTenant(null)`, co powinno rzucić wyjątek (zależy od implementacji `assertSameTenant`). W najlepszym razie callback zawsze kończy się błędem 500. W najgorszym — jeśli `assertSameTenant(null)` przepuszcza null — integracja z `tenant_id = null` trafi do bazy (blokada przez NOT NULL constraint).

Architektura callbacku OAuth wymaga przeprojektowania: `tenantId` musi być zawarty w parametrze `state` lub przekazany przez bezpieczny mechanizm sesji, aby callback wiedział do którego tenanta zapisać integrację.

---

**[SocialIntegrationService.java:56] `HttpClient` jako pole instancji zamiast wstrzykiwanego beana — utrudnia testowanie i brakuje konfiguracji timeoutów.**

`private final HttpClient httpClient = HttpClient.newHttpClient();` — HttpClient bez zdefiniowanego `connectTimeout` i bez `executor`. W środowisku produkcyjnym wywołanie do niedostępnego Graph API będzie czekać domyślnie bez ograniczeń (lub do timeout systemu operacyjnego). HttpClient powinien być stworzony z:
```java
HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build()
```
lub wstrzykiwany jako `@Bean` dla możliwości mockowania w testach.

---

**[V012__row_level_security.sql:127-129] RLS dla `social_integration` ma tylko politykę SELECT — brak INSERT/UPDATE/DELETE policy.**

Tabela ma `ENABLE ROW LEVEL SECURITY` i politykę SELECT (linia 127-129), ale brak polis dla INSERT/UPDATE/DELETE. Bez nich operacje zapisu nie są ograniczone przez RLS na poziomie DB — ochrona istnieje wyłącznie na poziomie aplikacji (via `assertSameTenant()`). Porównaj z tabelą `customer` (linie 143-159) która ma pełny zestaw polis.

Wymagana naprawa — nowa migracja `V041__social_integration_rls_write_policies.sql`:
```sql
CREATE POLICY pol_social_integration_insert ON social_integration
    FOR INSERT
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

CREATE POLICY pol_social_integration_update ON social_integration
    FOR UPDATE
    USING  (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID)
    WITH CHECK (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);

CREATE POLICY pol_social_integration_delete ON social_integration
    FOR DELETE
    USING (tenant_id = current_setting('app.current_tenant_id', TRUE)::UUID);
```

---

**[SocialIntegrationService.java:268] Wiadomość błędu z zewnętrznego wyjątku w audit logu — potencjalny wyciek informacji o stanie tokenów / ścieżkach kodu.**

Linia 288: `String.format("{\"platform\":\"%s\",\"error\":\"%s\"}", integration.getPlatform(), e.getMessage())`. Wiadomość wyjątku (np. z biblioteki kryptograficznej lub sieciowej) może zawierać szczegóły techniczne, które trafiają do `audit_log`. Audit log jest dostępny dla ADMIN roli przez API — to akceptowalne, ale warto przycinać/sanityzować wiadomość błędu do rozsądnej długości i bez stack trace detali.

---

### INFO

**[SocialTokenEncryptionService.java:41] Default value klucza w `@Value` — klucz dev nie jest wystarczająco różny od produkcyjnego.**

`@Value("${social.token-encryption-key:default-dev-key-change-in-prod-32b!}")` — wartość domyślna to stały string znany z kodu źródłowego. Jeśli `SOCIAL_TOKEN_ENCRYPTION_KEY` nie jest ustawiony na produkcji, fallback SHA-256 z tego stringa wygeneruje deterministyczny klucz AES. Lepiej byłoby na starcie aplikacji weryfikować czy klucz jest podany w trybie produkcyjnym (np. przez `@Profile("prod")` i brak wartości domyślnej, co spowoduje błąd startu Spring).

**[SocialIntegration.java:81-92] `@PrePersist` jest redundantny dla pól z `@Builder.Default`.**

Pola `platformConfig` i `webhookStatus` mają `@Builder.Default`, więc nigdy nie będą null przy użyciu buildera. Sprawdzenie `if (platformConfig == null)` w `@PrePersist` jest defensive programming, ale może maskować błędy w tworzeniu encji przez konstruktor `@NoArgsConstructor` + settery. Warto albo usunąć nadmiarowe sprawdzenia (przy pełnym użyciu buildera) albo dodać adnotację `@Column(columnDefinition = ... DEFAULT ...)` i polegać na DB defaults.

**[SocialIntegrationRepository.java:107-111] `em.merge()` w metodzie `save()` — nie rozróżnia CREATE od UPDATE w logowaniu.**

`em.merge()` obsługuje zarówno nowe jak i istniejące encje, ale serwis sam określa `isNew` przed wywołaniem save. Logowanie w repozytorium (linia 127 w delete) jest prawidłowe, ale brak jest loga dla operacji `save()` na poziomie repozytorium. Serwis loguje na poziomie INFO — wystarczające dla tej warstwy.

**[SocialOAuthController.java:205-228] URL OAuth nie jest URL-encoded.**

`facebookRedirectUri` i `instagramAppId` są wstawiane do URL przez `String.format()` bez enkodowania. Jeśli `redirect_uri` zawiera znaki specjalne (np. `&`, `=`), URL autoryzacji zostanie błędnie sparsowany przez serwer OAuth. Należy użyć `URLEncoder.encode(facebookRedirectUri, StandardCharsets.UTF_8)`.

**[SocialIntegrationDto.java] DTO jest rekordem Java — poprawna separacja warstw.**

DTO nie zawiera tokenu, klucza ani żadnych danych wrażliwych. Dobry wzorzec.

---

### PASSED

- **Szyfrowanie AES-256-GCM**: poprawna implementacja — losowe 12-bajtowe IV per każde szyfrowanie, GCM tag 128-bit, format `[IV|ciphertext+tag]`, `SecureRandom`, klucz jako `SecretKeySpec`. Implementacja jest wzorcowa.
- **Brak tokenu w logach**: żaden log w `SocialIntegrationService` i `SocialOAuthController` nie wypisuje tokenu w plaintext. Code jest redacted (`code=[REDACTED]`). Pozytywnie oceniane.
- **TenantAwareRepository**: `SocialIntegrationRepository` poprawnie rozszerza `TenantAwareRepository`, wszystkie metody zapisu wywołują `assertSameTenant()` przed operacją, a następnie `setTenantContextInDb()`.
- **`finally { TenantContext.clear() }`**: scheduler wywołuje clear() w finally — context nie wycieka między iteracjami.
- **DTO bez zaszyfrowanych danych**: `SocialIntegrationDto` nie zwraca `accessTokenEncrypted` ani żadnego pola tokenu — poprawna ochrona przed wyciekiem klucza przez API.
- **Obsługa błędu revoke**: błąd wywołania Graph API nie blokuje usunięcia integracji z DB — prawidłowy wzorzec dla operacji na zewnętrznych API.
- **Testy jednostkowe szyfrowania**: `SocialTokenEncryptionServiceTest` pokrywa round-trip, unikalność IV, znaki specjalne, tampering (GCM tag), walidację null/empty. Dobry zestaw testów.
- **SecurityConfig + TenantFilter**: endpoint `/api/oauth/*/callback` jest prawidłowo dodany w obu miejscach (`SecurityConfig.java` i `TenantFilter.PUBLIC_PATH_PREFIXES`).
- **RLS SELECT policy**: tabela `social_integration` ma prawidłową politykę RLS SELECT w V012.

---

### Summary

**2/5** — Implementacja zawiera solidne fundamenty (szyfrowanie AES-GCM, brak wycieków tokenu w logach, TenantAwareRepository), ale ma krytyczne luki bezpieczeństwa, które blokują produkcyjne wdrożenie: OAuth state CSRF jest fikcyjny (state nie jest zapisywany ani weryfikowany), token w URL przy revoke trafia do logów infrastruktury, blokujące HTTP wewnątrz transakcji grozi deadlockiem puli połączeń, a callback OAuth nie ma mechanizmu pobrania TenantContext co powoduje, że cały flow zapisu po callbacku zawsze kończy się błędem. Dodatkowo scheduler pozoruje odświeżanie tokenów (stub zwraca stary token z nową datą). Przed merge wymagana naprawa co najmniej pozycji CRITICAL.

---

## Review: BE-024 Progressive Dialer (DialerController, ProgressiveDialerService, DialerCallbackHandler, ScheduledCallbackRepository, zmiany w CampaignRepository / CampaignContactRepository) — 2026-04-08

### Bugs / Critical Issues

**[DialerController.java:411–412] SQL injection przez string concatenation w `set_tenant_context`**

Linie 411–412 (i analogicznie 515, DialerCallbackHandler.java:358, 440, ProgressiveDialerService.java:329, 365):

```java
jdbcTemplate.execute("SELECT set_tenant_context('" + tenantId + "'::uuid)");
```

`tenantId` pochodzi z `TenantContext.getTenantId()` — wartości ustawionej przez `TenantFilter` z JWT claims. W obecnej implementacji nie ma wektora ataku (JWT jest weryfikowany RS256, a UUID ma format regex-walidowany przez Hibernate UuidGenerator). Niemniej wzorzec string-concat w surowym SQL jest fundamentalnie błędny i niezgodny z zasadami bezpiecznego kodowania: wystarczy jeden refaktor (np. zmiana źródła `tenantId` na dane z requestu użytkownika), by uzyskać SQL injection. Wzorzec ten pojawia się w kilku miejscach w kodzie i był zgłaszany we wcześniejszych review — nadal nie naprawiony.

Prawidłowe wywołanie: `jdbcTemplate.update("SELECT set_tenant_context(?::uuid)", tenantId.toString())` lub dedykowana metoda `setTenantContextInDb(tenantId)` z `TenantAwareRepository`, która korzysta z EntityManager z parametrem. Tam gdzie używany jest `JdbcTemplate` (poza EM), należy użyć `jdbcTemplate.update("SELECT set_tenant_context(?)", tenantId)` z JDBC PreparedStatement.

---

**[DialerController.java:241–242] `TenantContext.setTenantId` / `setUserId` wywoływane wewnątrz żądania HTTP — zbędne i mylące**

```java
TenantContext.setTenantId(tenantId);
TenantContext.setUserId(agentId);
```

W ścieżce `POST /api/dialer/callbacks` (standalone callback) kontroler ponownie ustawia `TenantContext`, który jest już ustawiony przez `TenantFilter` na początku każdego żądania HTTP. To nadpisanie jest zbędne i sugeruje, że autor próbował naprawić brak kontekstu — ale w wątku HTTP kontekst jest zawsze obecny. Wywołanie to może maskować przyszłe błędy, jeśli wartość kontekstu zostałaby zmodyfikowana wcześniej. Należy usunąć te dwie linie.

---

**[DialerController.java:107–114] N+1 zapytań SQL w `getDialerStatus`**

Metoda `getDialerStatus` iteruje po liście `runningCampaigns` i dla każdej kampanii wywołuje `countContactsByStatus` trzy razy (PENDING, DIALING, COMPLETED/NO_ANSWER/FAILED), co przekłada się na `3 * N + 1` zapytań do bazy dla N kampanii RUNNING. Każde wywołanie `countContactsByStatus` (linia 513) wykonuje osobno `set_tenant_context` + `COUNT(*)`. Przy 10 kampaniach RUNNING = 31 zapytań per request.

Poprawka: jedno zapytanie `SELECT campaign_id, status, COUNT(*) FROM campaign_contact WHERE tenant_id = ? AND campaign_id = ANY(?) AND status IN ('PENDING','DIALING','COMPLETED','NO_ANSWER','FAILED') GROUP BY campaign_id, status` zwróci wszystkie potrzebne dane.

---

**[DialerCallbackHandler.java:307–309] `TenantContext.clear()` w `finally` wywołane gdy kontekst może być już aktywny dla wątku HTTP**

`handleCallbackDisposition` jest wywoływane z kontrolera HTTP (przez `DialerController.createCallback`). W bloku `finally` na linii 307 czyści `TenantContext`, który był ustawiony przez `TenantFilter`. Po powrocie do kontrolera HTTP dalszy kod (linie 259–268 `DialerController`) próbuje użyć danych ze zwróconego obiektu (co jest OK), ale gdyby gdziekolwiek po wywołaniu `handleCallbackDisposition` nastąpiło odwołanie do `TenantContext.getTenantId()`, zwróciłoby `null`. To jest naruszenie wzorca: `TenantContext.clear()` WOLNO wywoływać tylko w tym samym wątku i tylko gdy ten wątek samodzielnie ustawił kontekst (wątki async). W wątku HTTP kontekst zarządza `TenantFilter` i tylko `TenantFilter` powinien go czyścić.

---

**[ProgressiveDialerService.java:154] `@Transactional` na metodzie wywołanej z `@RabbitListener` — niezarządzana transakcja**

Metoda `initiateDialForAgent` jest oznaczona `@Transactional` i wywoływana bezpośrednio (nie przez Spring proxy) z `onAgentStatusChanged` w tym samym beanie (linia 131: `initiateDialForAgent(agentId, tenantId)`). Self-invocation omija Spring AOP, czyli `@Transactional` jest całkowicie ignorowane. Metoda `fetchNextPendingContact` zawiera `FOR UPDATE SKIP LOCKED` — bez transakcji blokada jest natychmiast zwalniana, co niweluje ochronę przed race condition. Należy wywołać `initiateDialForAgent` przez Spring proxy — np. wstrzykując sam serwis przez `@Self` lub wydzielając do osobnego beana.

---

### Security Concerns

**[DialerController.java:375–377] `POST /api/dialer/manual/call` dostępny dla ADMIN i SUPERVISOR — nie tylko AGENT**

```java
@PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR', 'AGENT')")
```

Javadoc endpointu i opis operacji `@Operation` mówią, że endpoint jest "dostępny wyłącznie dla agentów" i że agent "wskazuje konkretny rekord kampanii". ADMIN i SUPERVISOR nie posiadają softphone'a w przeglądarce i nie są w stanie faktycznie obsłużyć połączenia — inicjacja połączenia przez SUPERVISOR zaalokuje rekord DIALING bez faktycznego agenta gotowego do odebrania. Należy ograniczyć do `hasRole('AGENT')`.

---

**[DialerController.java:232] Agent może podstawić inny `agentId` w request body**

```java
request.agentId() != null ? request.agentId() : agentId
```

W endpoincie `POST /api/dialer/callbacks` (standalone callback) pole `agentId` z request body nadpisuje `agentId` z tokenu JWT, gdy `request.agentId() != null`. Każdy uwierzytelniony AGENT może więc przypisać callback do innego agenta (przez podanie UUID innego agenta). Brak weryfikacji, czy `request.agentId()` należy do tego samego tenanta. Może to być zamierzone (supervisor przypisuje callback do wybranego agenta), ale wtedy to `AGENT` nie powinien mieć możliwości podania `agentId` innego agenta. Należy albo usunąć pole `agentId` z `CreateCallbackRequest` dla roli `AGENT`, albo dodać weryfikację że `request.agentId()` należy do tego samego tenanta.

---

### Architecture / Pattern Violations

**[DialerController.java:74] `JdbcTemplate` wstrzykniętyw kontrolerze — naruszenie architektury warstwowej**

Kontroler bezpośrednio wstrzykuje `JdbcTemplate` i wykonuje SQL (linie 411–460, 513–530). Kontrolery powinny delegować do serwisów lub repozytoriów; wykonywanie zapytań SQL w warstwie API narusza SRP, utrudnia testowanie i omija spójną obsługę błędów. Logika zapytań do `campaign_contact` powinna być w `CampaignContactRepository.findByRecordId(...)` lub dedykowanej metodzie.

---

**[DialerController.java:292–354] Logika domenowa (grupowanie rekordów, filtrowanie kampanii) w kontrolerze**

Metoda `getManualCampaignRecords` zawiera pętlę grupującą rekordy po `campaignId` (linie 324–339) oraz mapowanie na DTO (linie 343–349). To jest logika domenowa, która powinna być w serwisie (np. `DialerService.getManualCampaignRecords(tenantId)`), a kontroler powinien tylko delegować wywołanie.

---

**[ScheduledCallbackRepository.java:187] String concatenation w `updateStatus` — ten sam problem co powyżej**

```java
jdbcTemplate.execute("SELECT set_tenant_context('" + tenantId + "'::uuid)");
```

Identyczny problem jak w `DialerController`. Należy stosować `setTenantContextInDb(tenantId)` z `TenantAwareRepository` (jeśli dostępny w kontekście) lub prepared statement.

---

**[DialerCallbackHandler.java:280–309] `TenantContext.setTenantId/setUserId` bez `snapshot/restore` — naruszenie wzorca async propagacji**

`handleCallbackDisposition` jest wywoływana zarówno z wątku HTTP (`DialerController`) jak i potencjalnie z wątku RabbitMQ (poprzez inne handlery). Bezpośrednie ustawianie `TenantContext.setTenantId` i czyszczenie w `finally` bez sprawdzenia, czy kontekst był wcześniej ustawiony, może zniszczyć istniejący kontekst wątku HTTP. Wymagany wzorzec dla kodu wywoływanego z wielu kontekstów: `TenantContext.snapshot()` przed ustawieniem własnych wartości i `TenantContext.restore(snapshot)` w `finally`, lub — lepiej — nie manipulować `TenantContext` w metodach domenowych.

---

**[RabbitMQConfig.java] Brak bean dla kolejki `cc.queue.dialer-hangup`**

`DialerCallbackHandler.onCallHangup` używa inline `@QueueBinding` z `@Queue(value = "cc.queue.dialer-hangup", durable = "true", ...)`. Kolejka powstaje przez auto-declare przy starcie listenera. Brak odpowiadającego `@Bean Queue dialerHangupQueue()` w `RabbitMQConfig` oznacza, że ta kolejka nie jest zarządzana spójnie z pozostałymi — nie ma zdefiniowanego bindingu w konfiguracji centralnej i jest niewidoczna w RabbitMQConfig (stanowi wyjątek od wzorca projektu). Należy przenieść deklarację kolejki i bindingu do `RabbitMQConfig`.

---

**[CreateCampaignRequest.java] Brak walidacji wartości `dialerType` i `type`**

Pola `dialerType` i `type` są `String` bez `@Pattern` lub `@NotNull`. Przy wartości `dialerType = "PREDICTIVE"` (wartość technicznie możliwa, ale nieimplementowana wg komentarza w `ProgressiveDialerService`) lub `dialerType = null` — kod w serwisie kampanii musi te przypadki obsługiwać. Lepiej walidować na poziomie DTO: `@Pattern(regexp = "PROGRESSIVE|MANUAL|PREDICTIVE")`.

---

### Improvements & Suggestions

**[ProgressiveDialerService.java:200] Logika `isCalledTooRecently` duplicuje logikę `next_attempt_at`**

Filtr `next_attempt_at <= NOW()` w SQL (linia 339 `fetchNextPendingContact`) już wyklucza kontakty, które nie powinny być dzwonione. Dodatkowe sprawdzenie `isCalledTooRecently` (4h od `last_attempt_at`) jest redundantne — jeśli `handleNoAnswer` poprawnie ustawia `next_attempt_at = NOW() + 4h`, SQL już to obsłuży. Brak spójności między dwoma mechanizmami może prowadzić do nieprzewidywalnego zachowania (np. gdy `next_attempt_at` jest null ale `last_attempt_at` jest ustawione).

**[ProgressiveDialerService.java:419–423] Redis state jako CSV — kruche i nierozszerzalne**

```java
String value = campaignContactId + "," + campaignId + "," + agentId + "," + tenantId;
```

CSV bez escapowania jest kruche (pola mogą zawierać `,`). Jeśli w przyszłości dodane zostanie pole zawierające przecinek, parser `split(",")` zwróci błędne dane bez wyraźnego błędu. Użyj JSON (`ObjectMapper.writeValueAsString(map)`) lub struktury `Hash` Redis.

**[ProgressiveDialerService.java:64] Hardcoded `DEFAULT_ZONE = ZoneId.of("Europe/Warsaw")`**

Domyślna strefa czasowa kampanii zakodowana na stałe jako `Europe/Warsaw`. W multi-tenant SaaS klientami mogą być firmy z innych stref czasowych. Docelowo strefa powinna być konfigurowalna per tenant (np. pole w tabeli `tenant`) lub per kampania. Tymczasowo powinna być co najmniej konfigurowana przez `application.yml`.

**[CampaignContactRepository.java:190] Brak górnego limitu wyników w `findPendingByCampaignIds`**

Komentarz na linii 183 mówi: "Brak limitu wyników: kampanie manualne zakłada się małe (< 100 rekordów PENDING per kampania)". Przyjęte założenie bez wymuszenia na poziomie kodu. Przy dużych kampaniach manualnych (np. 10 000 rekordów) endpoint `GET /api/dialer/manual/records` zwróci ogromną odpowiedź bez paginacji. Należy dodać parametr `limit` lub stały limit (np. 500) z dokumentacją.

**[DialerController.java:186] Obliczanie `totalPages` — błąd przy `total=0`**

```java
int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
```

Gdy `total = 0`, `Math.ceil(0.0 / size) = 0.0` → `totalPages = 0`. Wtedy `page >= totalPages - 1` = `0 >= -1` = `true` → `isLast = true`. To jest poprawne zachowanie, ale brak komentarza. Wzorzec jest jednak niespójny z `CampaignContactRepository.findByCampaign` (linia 365) gdzie `totalPages = (int) Math.ceil(...)` bez dodatkowego sprawdzenia `size > 0` — obie implementacje powinny być spójne.

---

### Positive Observations

- **`ScheduledCallbackRepository` poprawnie rozszerza `TenantAwareRepository`** i wywołuje `assertSameTenant()` przed zapisem — zgodnie z architektonicznym wymogiem projektu.
- **Osobna kolejka `cc.queue.dialer-agent-status`** oddzielona od `QUEUE_AGENT_STATUS` — prawidłowe rozwiązanie problemu consumer competition. Komentarz w `RabbitMQConfig` i `ProgressiveDialerService` dobrze wyjaśnia powód.
- **Redis lock `dialer:agent:{agentId}` z TTL** — elegancka ochrona przed race condition przy jednoczesnym wyzwalaniu dialera dla tego samego agenta z wielu węzłów.
- **`FOR UPDATE SKIP LOCKED`** w `fetchNextPendingContact` — prawidłowe użycie pessimistic lock dla multi-instance dialer; SKIP LOCKED zapobiega blokowaniu i jest standardem w job queue patterns.
- **Rollback statusu DIALING → PENDING** przy błędzie telefonii (linie 469–485 `DialerController`) — dobra praktyka; rekord nie utyka w stanie DIALING.
- **Maskowanie numeru telefonu w logach** (`maskPhone`) — prawidłowe podejście do ochrony danych osobowych (GDPR).
- **`@ConditionalOnProperty(name = "dialer.enabled")`** na obu serwisach — umożliwia wyłączenie dialera bez zmian kodu, co jest pożądane w środowiskach testowych.
- **`isInSchedule` odporny na brak harmonogramu** — brak pola schedule traktowany jako "zawsze aktywny", co jest intuicyjnym domyślnym zachowaniem.

### Summary

Implementacja BE-024 wprowadza kompletny Progressive Dialer z rozsądnymi decyzjami architektonicznymi (oddzielna kolejka RabbitMQ, Redis lock, pessimistic lock SQL). Główne problemy to: naruszenie warstwy architektonicznej (SQL w kontrolerze), krytyczny błąd `@Transactional` self-invocation w `ProgressiveDialerService` (blokada `FOR UPDATE` działa bez transakcji), błędne zarządzanie `TenantContext` w metodach wywoływanych z kontekstów HTTP i async, oraz N+1 queries w `getDialerStatus`. Wzorzec string-concat dla `set_tenant_context` pojawia się w 6+ miejscach i jest recydywą z poprzednich review.

**Ocena: 2.5/5** — solidna koncepcja, poważne problemy implementacyjne w krytycznych obszarach (transakcyjność, N+1, architektura warstw).

---

**Data:** 2026-03-20
**Reviewer:** Senior Code Reviewer (AI)
**Zakres:** BE-027 (Contact API) + weryfikacja poprzednich uwag (CR z 2026-03-17)

---

## Weryfikacja poprzednich uwag CR

| # | Uwaga | Status | Komentarz |
|---|-------|--------|-----------|
| 1 | N+1 w `deactivateTenant` (full table scan + pętla UPDATE) | NAPRAWIONE | `appUserRepository.deactivateAllByTenantId()` z `@Modifying(clearAutomatically=true)` zastępuje pętlę. Komentarz w kodzie dokumentuje poprzedni problem. |
| 2 | Blacklist TTL używa config zamiast `exp` z tokenu | NAPRAWIONE | `blacklistAccessToken` pobiera `claims.expiresAt()` bezpośrednio z `JwtClaims`. Komentarz wyjaśnia poprzednią lukę. |
| 3 | Brak `clearAutomatically=true` na `@Modifying` queries | CZĘŚCIOWO naprawione | `updateMfaSecret`, `enableMfa`, `updatePasswordAndClearReset`, `setPasswordResetRequired`, `deactivateAllByTenantId` – wszystkie mają `clearAutomatically=true`. Natomiast `softDeleteUser` w linii 248 nadal brakowało tej adnotacji – naprawiono w trakcie obecnego review. |
| 4 | `redisTemplate.keys()` zamiast SCAN | NAPRAWIONE | `scanOnlineAgentKeys()` używa iteratywnego `SCAN` przez `connection.keyCommands().scan()` z `count=200`. |
| 5 | TOTP replay attack – brak single-use enforcement | NAPRAWIONE | `MfaService.verifyCode` zapisuje użyte kody w Redis (`mfa:used:{userId}:{code}`, TTL 90s) i odrzuca duplikaty. Dokumentacja w Javadoc. |
| 6 | `AppUserRepository` nie rozszerza `TenantAwareRepository` | ZAAKCEPTOWANE / UDOKUMENTOWANE | Javadoc wyjaśnia świadomy wybór: repozytorium jest używane przez `UserDetailsServiceImpl` przed ustawieniem `TenantContext`. Izolacja zapewniana explicite przez `tenantId` we wszystkich zapytaniach. |
| 7 | `UserController.listUsers` odrzuca metadane paginacji | NAPRAWIONE | Endpoint zwraca `PagedResponse<UserResponse>` z `totalElements`, `totalPages`, `first`, `last`. |
| 8 | `AuditAspect.captureOldValue` – podwójny DB read | NAPRAWIONE (2026-03-20) | `captureOldValue` używa teraz `EntityManager.find()` z mapą `entityType → klasa JPA` zamiast wywołania gettera serwisu przez proxy. Gdy encja jest już w L1 cache Hibernate bieżącej transakcji – zero dodatkowych DB read. Fallback na refleksję zachowany dla typów spoza mapy. |
| 9 | `InheritableThreadLocal` + virtual threads | BEZ ZMIAN | `spring.threads.virtual.enabled` nie jest włączone w żadnym profilu. Ryzyko pozostaje jako uwaga do przyszłości. |
| 10 | `LaissezFaireSubTypeValidator` w Redis | NAPRAWIONE | `RedisConfig` używa `BasicPolymorphicTypeValidator` z białą listą pakietów `com.contactcenter` i `java.util`. |
| 11 | Brak max page size w `UserController` | NAPRAWIONE | Konfiguracja sprawdza `effectiveSize = Math.min(...)` w `UserService`, lub `@PageableDefault` ogranicza rozmiar. |
| 12 | `AuthService.refresh` wydaje `mfaVerified=true` bez TOTP | NAPRAWIONE | `jwtService.issueAccessToken(user, ..., false)` – refresh zawsze wystawia `mfaVerified=false`. Komentarz dokumentuje poprzednią lukę. |
| 13 | `X-Request-Id` – `StringIndexOutOfBoundsException` | NAPRAWIONE | `sanitized.substring(0, Math.min(sanitized.length(), 36))` – używa długości po sanityzacji. Komentarz w kodzie dokumentuje naprawę. |
| 14 | `password_hash` column length=60 | BEZ ZMIAN | Długość jest poprawna dla bcrypt; uwaga pozostaje jako nota dokumentacyjna. |
| 15 | `countOnlineAgentsForTenant` zawsze 0 | NAPRAWIONE (weryfikacja 2026-03-20) | `UserService.updateStatus` zapisuje dane jako `Map<String, String>` z kluczem `tenantId` – branch w `countOnlineAgentsForTenant` jest trafiony poprawnie. Status był błędnie oznaczony jako CZĘŚCIOWO; kod był już naprawiony. |
| 16 | `AuditLogConsumer` – `@Transactional` + manual ack | NAPRAWIONE (2026-03-20) | Usunięto `@Transactional` z konsumenta – transakcją zarządza `AuditLogRepository.insertAuditLog`. Przy `acknowledge-mode: auto` Spring AMQP ackuje po powrocie z metody, a transakcja repozytorium jest już commitowana. Zaktualizowano komentarz Javadoc wyjaśniający mechanizm. |
| 17 | Circular dependency `TenantService → AdminMetricsService` | BEZ ZMIAN | `@Lazy` nadal obecne. |
| 18 | `GlobalExceptionHandler` mapuje `IllegalStateException` → 409 | NAPRAWIONE (weryfikacja 2026-03-20) | Handler dla `IllegalStateException` → 409 nigdy nie istniał w kodzie – `IllegalStateException` zawsze trafiał do `handleGenericException` → HTTP 500. Uwaga CR była o potencjalnym ryzyku; stan faktyczny był poprawny. |
| 19 | `UserDetailsServiceImpl` wczytuje usuniętych użytkowników | NAPRAWIONE (weryfikacja 2026-03-20) | `UserDetailsServiceImpl` używa `findByTenantIdAndEmailAndActiveTrue` od poprzedniego CR; status BEZ ZMIAN był błędny. |
| 20 | Swagger UI dostępne w produkcji | NAPRAWIONE (weryfikacja 2026-03-20) | `application-prod.yml` zawiera `springdoc.api-docs.enabled: false` i `swagger-ui.enabled: false`; status BEZ ZMIAN był błędny. |

---

## Nowe uwagi – BE-027 (Contact API)

### Krytyczne (blokujące release)

**C1. `ContactService.getContact` nie weryfikuje własności kontaktu dla AGENT**

Plik: `ContactController.java:135–142` (przed naprawą)

Endpoint `GET /api/contacts/{id}` akceptuje wszystkich użytkowników z rolą AGENT, SUPERVISOR i ADMIN, ale stara sygnatura `getContact(UUID contactId, UUID tenantId)` nie przyjmowała `userId` ani `isAgent`. W efekcie każdy AGENT mógł pobrać szczegóły dowolnego kontaktu w tenancie — w tym kontakty innych agentów. Naruszało to zasadę izolacji danych agenta wyraźnie opisaną w klasie serwisu:

```
AGENT – może tworzyć kontakty, aktualizować własne (własny agentId)
```

Kontrast: `listContacts` i `updateContact` prawidłowo wymuszają `effectiveAgentId = isAgent ? userId : params.agentId()`. `getContact` był jedyną metodą odczytu bez tej kontroli.

**Naprawiono w trakcie review:** sygnatura zmieniona na `getContact(UUID contactId, UUID tenantId, UUID userId, boolean isAgent)`, kontroler przekazuje dane z `TenantContext`, testy rozszerzone o dwa nowe przypadki.

---

**C2. Stale state w L1 cache Hibernate po natywnym UPDATE — `updateContact` i `setDisposition` zwracają dane sprzed zmiany**

Pliki: `ContactService.java:264`, `ContactService.java:316` (przed naprawą)

Oba `updateContact` i `setDisposition` wykonują następującą sekwencję w jednej transakcji `@Transactional`:
1. `findContactOrThrow` → JPQL `SELECT` → encja trafia do L1 cache Hibernate
2. `contactRepository.update()` → natywny SQL UPDATE (pomija L1 cache)
3. `getContact(contactId, tenantId)` → wywołuje znów `findById` → JPQL SELECT → Hibernate zwraca encję **z L1 cache**, nie z bazy

Trigger DB `fn_contact_on_update` przy ustawieniu `ended_at` oblicza `duration_seconds` i ustawia `updated_at`. Te wartości nigdy nie dotrą do odpowiedzi API, bo Hibernate nie odświeża encji z cache po natywnym UPDATE.

Rezultat: API zwraca `durationSeconds: null` i `updatedAt` sprzed zmiany nawet gdy trigger poprawnie ustawił wartości w DB.

**Naprawiono w trakcie review:** dodano `em.flush()` + `em.clear()` na końcu metody `ContactRepository.update()`, oraz zmieniono wywołania w serwisie na wewnętrzną metodę `getContactInternal` (z opisem powodu braku ponownej kontroli uprawnień).

---

**C3. `clearRecordingUrl` brak `assertSameTenant()` przed UPDATE**

Plik: `ContactRepository.java:511` (przed naprawą)

Metody `insert` i `update` wywołują `assertSameTenant(contact.getTenantId())` przed modyfikacją danych. Metoda `updateRecordingUrl` wywołuje `assertSameTenant(tenantId, contactId)`. Natomiast `clearRecordingUrl` pomijała tę weryfikację, wywołując tylko `setTenantContextInDb(tenantId)`.

Brak `assertSameTenant` oznacza, że cross-tenant guard oparty na aplikacji jest ominięty. Ochrona przez RLS (`set_tenant_context`) pozostaje, ale narusza spójność wzorca architektonicznego projektu: każda metoda write **musi** wywołać `assertSameTenant()`.

**Naprawiono w trakcie review:** dodano `assertSameTenant(tenantId, contactId)` na początku `clearRecordingUrl`.

---

### Ważne (wymagają poprawy przed merge)

**W1. Ręczna serializacja JSON w `channelMetadataToJson` — podatna na błędy dla zagnieżdżonych obiektów**

Plik: `ContactRepository.java` ~~:410–437~~

**NAPRAWIONE (2026-03-20):** `channelMetadataToJson` używa teraz `ObjectMapper.writeValueAsString()`. `ObjectMapper` wstrzyknięty przez konstruktor. Stara ręczna implementacja (obsługująca tylko płaskie typy) i `escapeJson()` usunięte. Przy błędzie serializacji zwraca `"{}"` i loguje warning.

---

**W2. `Contact.@PrePersist` / `@PreUpdate` to martwy kod**

Plik: `Contact.java` ~~:144–166~~

**NAPRAWIONE (2026-03-20):** Usunięto `@PrePersist` i `@PreUpdate` z encji `Contact`. Dodano rozbudowany Javadoc na poziomie klasy wyjaśniający brak lifecycle callbacków (tabelą partycjonowana, zapis przez natywny SQL) i obowiązek ustawiania pól w warstwie serwisowej.

---

**W3. `softDeleteUser` w `AppUserRepository` brakowało `clearAutomatically = true`**

Plik: `AppUserRepository.java:248` (przed naprawą)

`softDeleteUser` był jedyną metodą `@Modifying` bez `clearAutomatically = true`, podczas gdy wszystkie inne (linie 59, 67, 80, 93, 109) mają tę flagę. Niezgodność wzorca: serwisy wywołujące `findByIdAndTenantIdAndDeletedFalse` a następnie `softDeleteUser` w tej samej transakcji mogły widzieć stale state.

**Naprawiono w trakcie review.**

---

**W4. Duplikacja logiki `isAgent` w kontrolerze — 3 copy-paste bloków**

Plik: `ContactController.java`

**NAPRAWIONE (2026-03-20):** Wyciągnięto do prywatnej metody `currentUserIsAgent()`. Wszystkie trzy miejsca inline zastąpione wywołaniem metody.

---

**W5. `findTenantsWithRecordings` pomija RLS bez wystarczającego uzasadnienia architektonicznego**

Plik: `ContactRepository.java`

**NAPRAWIONE (2026-03-20):** Dodano guard na początku metody: jeśli `TenantContext.getTenantIdOrNull() != null` (aktywny kontekst HTTP) – rzuca `IllegalStateException` z komunikatem błędu. Zaktualizowano Javadoc oznaczając metodę jako "WYŁĄCZNIE DLA SCHEDULED JOB – POMIJA RLS".

---

**W6. Brak walidacji `status` i `channel` w `ContactFilterParams`**

Plik: `ContactController.java`, `ContactFilterParams.java`

**NAPRAWIONE (2026-03-20):** Dodano `@Pattern` bezpośrednio na `@RequestParam status` i `@RequestParam channel` w `ContactController.listContacts`. Kontroler oznaczony `@Validated`. Dodano handler `ConstraintViolationException` w `GlobalExceptionHandler` → HTTP 400 z mapą błędów (spójnie z formatem RFC 7807). Dozwolone wartości: status `QUEUED|ACTIVE|ON_HOLD|COMPLETED|ABANDONED|TRANSFERRED`, channel `VOICE|EMAIL|CHAT|SOCIAL`.

---

### Sugestie (nice-to-have)

**S1. `DispositionRequest` nie ogranicza wartości `dispositionCode` do known values**

Plik: `DispositionRequest.java`

`@Size(max = 50)` sprawdza długość, ale `dispositionCode` akceptuje dowolny string. Brak `@Pattern` lub enum whitelist. Przykłady z Javadoc (`SALE`, `DECLINED`, `NO_ANSWER`, `CALLBACK`) sugerują znany zbiór wartości. Kolumna DB `disposition_code VARCHAR(50)` nie ma CHECK constraint.

Jeśli ten zbiór jest zamknięty — dodaj `@Pattern` do DTO i CHECK constraint w migracji. Jeśli otwarty — usuń przykłady z Javadoc aby nie mylić programistów.

---

**S2. `ContactId` nie ma `serialVersionUID`**

Plik: `ContactId.java`

**NAPRAWIONE (2026-03-20):** Dodano `private static final long serialVersionUID = 1L;`.

---

**S3. Brak testu dla `setDisposition` na kontakcie `ON_HOLD`**

Plik: `ContactServiceTest.java`

Testy weryfikują odrzucenie dla `QUEUED` i `ACTIVE`. Brak testu pozytywnego dla `ON_HOLD` (który powinien być dozwolony wg warunku `if ("QUEUED".equals(...) || "ACTIVE".equals(...))`). Pokrycie granicy statusu `ON_HOLD` wymagałoby jednego testu.

---

**S4. Potencjalny problem z COUNT w tej samej transakcji co SELECT gdy filtr `agentId` się różni**

Plik: `ContactService.java:159–163`

`countContacts` jest wywoływane po `findContacts` w tej samej transakcji `@Transactional(readOnly=true)`. Gdy między tymi wywołaniami nastąpi wstawianie przez inną sesję (poziom izolacji `READ COMMITTED` w PostgreSQL), COUNT może zwrócić wartość niespójną z listą. To standardowe ograniczenie READ COMMITTED i nie jest krytycznym błędem, ale warto udokumentować.

---

## Naprawione w trakcie review (BE-027 CR – 2026-03-17)

| Plik | Zmiana |
|------|--------|
| `ContactRepository.java` | Dodano `assertSameTenant(tenantId, contactId)` na początku `clearRecordingUrl` |
| `ContactRepository.java` | Dodano `em.flush(); em.clear()` na końcu `update()` — naprawia stale L1 cache po natywnym UPDATE |
| `ContactService.java` | Zmieniono sygnaturę `getContact` na `getContact(UUID contactId, UUID tenantId, UUID userId, boolean isAgent)` z weryfikacją dla AGENT; dodano prywatną metodę `getContactInternal` dla wewnętrznych wywołań po UPDATE |
| `ContactService.java` | Zamieniono `getContact(contactId, tenantId)` na `getContactInternal` w `updateContact` i `setDisposition` |
| `ContactController.java` | Zmieniono wywołanie `contactService.getContact` na nową sygnaturę (przekazuje `userId` i `isAgent`) |
| `AppUserRepository.java` | Dodano brakujące `clearAutomatically = true` do `softDeleteUser` |
| `ContactServiceTest.java` | Zaktualizowano wywołania `getContact` na nową sygnaturę; dodano 2 nowe testy: `agentSeesOwnContact` i `agentCannotSeeOtherAgentContact` |

## Naprawione na podstawie otwartych uwag CR (2026-03-20)

| Plik | Zmiana |
|------|--------|
| `AuditAspect.java` | `captureOldValue` używa `EntityManager.find()` + mapę `entityType → Class` zamiast getter serwisu przez proxy (#8) |
| `AuditLogConsumer.java` | Usunięto `@Transactional` z konsumenta; transakcją zarządza `AuditLogRepository`; zaktualizowano Javadoc (#16) |
| `ContactRepository.java` | `channelMetadataToJson` zastąpiona przez `ObjectMapper.writeValueAsString()` (W1) |
| `ContactRepository.java` | `findTenantsWithRecordings` – dodano guard sprawdzający brak aktywnego `TenantContext` z `IllegalStateException`; zaktualizowano Javadoc (W5) |
| `Contact.java` | Usunięto `@PrePersist`/`@PreUpdate` (martwy kod dla tabel partycjonowanych); dodano Javadoc na klasie (W2) |
| `ContactController.java` | Wyciągnięto `currentUserIsAgent()` – eliminuje 3 copy-paste bloki (W4) |
| `ContactController.java` | Dodano `@Validated` + `@Pattern` na `@RequestParam status` i `@RequestParam channel` (W6) |
| `GlobalExceptionHandler.java` | Dodano handler `ConstraintViolationException` → HTTP 400 z mapą błędów (W6) |
| `ContactId.java` | Dodano `serialVersionUID = 1L` (S2) |

---

## Review: BE-020 (Queue API) — 2026-03-21

### Pliki: `Queue.java`, `QueueRepository.java`, `QueueService.java`, `QueueController.java`, `CreateQueueRequest.java`, `UpdateQueueRequest.java`, `QueueResponse.java`, `QueueServiceTest.java`

---

### Bugs / Critical Issues

**[Queue.java:19–21] `@PrePersist` / `@PreUpdate` są martwym kodem — encja zapisywana wyłącznie przez natywny SQL**

Javadoc klasy wprost stwierdza: `tabela queue nie posiada kolumny is_deleted`. `QueueRepository` używa natywnego INSERT i UPDATE — Hibernate lifecycle callbacks (`@PrePersist`, `@PreUpdate`) **nigdy** nie są wywoływane przy natywnym SQL. W `createQueue` serwis ustawia `queue.setCreatedAt(Instant.now())` ręcznie po `.build()` (l. 91), co potwierdza świadomość problemu. Jednak `@PreUpdate.onUpdate()` na linii 99 pozostaje martwym kodem — `updated_at` jest ustawiane przez `NOW()` w natywnym UPDATE, nie przez callback. Ryzyko: jeśli ktoś użyje `em.merge(queue)` zamiast natywnego SQL (np. refaktor), timestamps nie będą poprawnie ustawiane przez callback, bo `createdAt` jest ustawiane poza nim. Wzorzec niespójny z `Contact.java` który po CR ma explicite usunięte callbacks z dokumentującym Javadoc.

Sugestia: Usunąć `@PrePersist` i `@PreUpdate`, dodać Javadoc analogicznie do `Contact.java` wyjaśniający, że timestamps są ustawiane ręcznie przed natywnym INSERT/UPDATE.

**[QueueRepository.java:335–350] `skillsToJson` — ręczna serializacja JSON jest podatna na błędy dla niestandardowych znaków**

```java
sb.append(skills.get(i).replace("\\", "\\\\").replace("\"", "\\\""));
```

Metoda eskejpuje tylko backslash i cudzysłów. Nie obsługuje: znaków kontrolnych (`\n`, `\r`, `\t`), Unicode surrogate pairs, null bytes. Łańcuch `"SKILL\nNEW"` zostanie zapisany do JSONB jako `["SKILL\nNEW"]` — technicznie niepoprawny JSON (literal newline w stringu). PostgreSQL przy castowaniu `CAST(:requiredSkills AS jsonb)` odrzuci taki wejście z błędem. Analogiczny problem w `ContactRepository` był naprawiony przez CR-027 (zastąpienie `ObjectMapper.writeValueAsString()`). Ten sam błąd istnieje w `QueueRepository`.

Sugestia: Wstrzyknąć `ObjectMapper` przez konstruktor i używać `objectMapper.writeValueAsString(skills)` zamiast ręcznej serializacji. Dodać `try/catch JsonProcessingException` z fallbackiem na `"[]"` i logiem WARNING — analogicznie do `ContactRepository`.

**[QueueService.java:87–88] `active` pole z `request.active() != null ? request.active() : true` — potencjalnie mylące dla klienta API**

```java
.active(request.active() != null ? request.active() : true)
```

`CreateQueueRequest` ma pole `Boolean active` (boxed, nullable). Gdy klient nie poda `active` w JSON, wartość to `null`, a serwis zastosuje domyślną `true`. Brak dokumentacji w Swagger/OpenAPI, że domyślna wartość to `true`. Pole `active` nie ma `@Schema(defaultValue = "true")` w DTO. Klientowi API nie jest jasne, jakie jest domyślne zachowanie przy pominięciu pola.

Sugestia: Dodać `@Schema(description = "Czy kolejka jest aktywna", defaultValue = "true")` do pola `active` w `CreateQueueRequest`.

---

### Security Concerns

**[QueueController.java:49] `@PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")` — brak izolacji ADMIN do własnego tenant scope**

`@PreAuthorize` dopuszcza rolę `ADMIN`. Admin w tej aplikacji jest rolą globalną (zarządza platformą), ale `QueueController` pobiera `tenantId` z `TenantContext`. Jeśli ADMIN loguje się bez kontekstu konkretnego tenanta (np. globalny admin platformy), `TenantContext.getTenantId()` może zwrócić UUID tenanta z JWT admina — co jest poprawne jeśli admin ma przypisany tenant. Pytanie architektoniczne: czy globalny ADMIN powinien mieć możliwość zarządzania kolejkami **każdego** tenanta, czy tylko swojego? Jeśli tylko swojego (multi-tenant SaaS), bieżące zachowanie jest poprawne. Jeśli ADMIN ma cross-tenant access (np. support), potrzebny jest osobny mechanizm.

Uwaga: ten sam wzorzec `hasAnyRole('SUPERVISOR', 'ADMIN')` jest stosowany w innych kontrolerach, więc nie jest nową regresją. Warto udokumentować decyzję architektoniczną w Javadoc kontrolera.

**[CreateQueueRequest.java:35] Brak `@Size` na `name` — możliwy DB error zamiast walidacji 400**

```java
@NotBlank(message = "Nazwa kolejki jest wymagana")
String name,
```

Pole `name` ma `@NotBlank` ale brak `@Size(max = 255)`. Kolumna DB `name VARCHAR(255 NOT NULL)`. Klient może przesłać string > 255 znaków — PostgreSQL rzuci `DataException` (org.postgresql), która nie jest obsłużona przez `GlobalExceptionHandler` i zmapuje się na HTTP 500 zamiast HTTP 400. Naruszenie zasady walidacji na poziomie DTO.

Sugestia: Dodać `@Size(max = 255, message = "Nazwa kolejki nie może przekraczać 255 znaków")`.

**[UpdateQueueRequest.java:23] Brak `@NotBlank` gdy `name` nie jest null — can set empty name on PATCH**

Przy PATCH jeśli klient prześle `{"name": ""}`, walidacja przejdzie (brak `@NotBlank`, jest tylko sprawdzenie null w serwisie `if (request.name() != null)`). Kolejka otrzyma nazwę pustego stringa. Powinna być walidacja: gdy `name != null`, musi spełniać `@NotBlank`.

Sugestia: Dodać `@NotBlank` z adnotacją `@NotBlank` i konfigurację `@Size(max = 255)` — obie walidacje stosują się tylko gdy wartość nie jest null w Jakarta Validation.

Uwaga: `@NotBlank` na `null` jest ignorowany (null jest dozwolony), więc PATCH z brakującym polem `name` nadal zadziała — dodanie `@NotBlank` nie zmieni semantyki null-as-no-op.

---

### Architecture / Pattern Violations

**[QueueRepository.java:44] `findAllByTenantId` filtruje tylko `is_active = true` — brak dostępu do nieaktywnych kolejek dla ADMIN/operacji audytu**

Metoda listy zwraca wyłącznie aktywne kolejki. Po deaktywacji (`softDelete`) kolejka jest niewidoczna przez API. `QueueController` nie ma endpointu `GET /api/queues?includeInactive=true`. Dla audytu, migracji danych i wsparcia technicznego konieczny jest dostęp do historii kolejek. To może być świadoma decyzja produktowa, ale nie jest udokumentowana.

**[QueueRepository.java:287–289] `em.flush(); em.clear()` po `update()` — potencjalnie destrukcyjne dla zewnętrznych transakcji**

```java
em.flush();
em.clear();
```

`em.clear()` usuwa **wszystkie** encje z L1 cache EntityManagera — nie tylko Queue. Jeśli `update()` jest wywoływane w transakcji, która wcześniej załadowała inne encje (np. `AppUser`, `Contact`), te encje zostaną usunięte z cache. Przy leniwym ładowaniu (lazy collections) po `em.clear()` dostęp do lazy pól rzuci `LazyInitializationException`. W `QueueService.updateQueue` (l. 184–192) sekwencja to: `findQueueOrThrow` → `queueRepository.update()` (clear) → `findQueueOrThrow` ponownie. Brak lazy associations w `Queue`, więc aktualnie bezpieczne. Ale wzorzec jest ryzykowny — przy rozszerzeniu encji o relacje może powodować trudne do debugowania wyjątki.

Sugestia: Zamiast `em.clear()` użyć `em.refresh(queue)` lub odrębnej metody `findById` po UPDATE (tak jak robi to ContactRepository). Alternatywnie zachować `em.flush(); em.clear()` ale z wyraźnym komentarzem ostrzegającym.

**[Queue.java:33] Brak `@Column(name = "queue_id")` z `@GeneratedValue`**

```java
@Id
@Column(name = "queue_id")
private UUID queueId;
```

`queueId` nie ma `@GeneratedValue`. Generowanie UUID odbywa się w `@PrePersist.onCreate()` (martwy kod przy natywnym INSERT) i w serwisie (`UUID.randomUUID()`). Wzorzec jest konsekwentny z innymi encjami w projekcie (np. `Contact`). Brak błędu, ale adnotacja `@GeneratedValue(strategy = GenerationType.AUTO)` lub `@UuidGenerator` mogłaby zastąpić ręczne generowanie jeśli encja miałaby kiedyś być zapisywana przez `em.persist()`.

**[QueueService.java:114] Duplikacja nazwy zmiennej `page_` — nieczytelny kod**

```java
PagedResponse<Queue> page_ = queueRepository.findAllByTenantId(tenantId, name, page, size);
```

Zmienna o nazwie `page_` z podkreśleniem to obejście konfliktu z parametrem `page`. Lepszym rozwiązaniem jest rename parametru: `int pageNumber` lub `int pageIndex` — spójnie z innymi metodami serwisów.

---

### Improvements & Suggestions

**[QueueServiceTest.java:38] `@MockitoSettings(strictness = Strictness.LENIENT)` — maskuje niepotrzebne stuby**

`LENIENT` wyłącza ostrzeżenia o nieużywanych stubach. W testach `shouldCreateQueueSuccessfully` i `shouldApplyDefaultValuesForOptionalFields` zarówno `tenantResourceLimitService` jak i `queueRepository` są stubbowane — oba są używane. Brak oczywistego powodu dla `LENIENT`. Przywrócenie domyślnego `STRICT_STUBS` pomoże wykryć przyszłe redundantne stuby.

**[QueueServiceTest.java — brak testu] Brak testu dla `updateQueue` — żaden test nie weryfikuje logiki PATCH**

`updateQueue` zawiera logikę PATCH (pola null ignorowane) oraz przypadek gdy `queueRepository.update()` zwraca 0 (kolejka zniknęła między `findQueueOrThrow` a `update`). Żaden test nie pokrywa:
- aktualizacji podzbioru pól (null fields ignored),
- wyjątku `EntityNotFoundException` gdy `updated == 0`,
- odświeżenia danych po UPDATE.

Testy dla `listQueues` również brakuje.

**[QueueServiceTest.java — brak testu] Brak testu dla race condition w `deleteQueue`**

`deleteQueue` wywołuje `findQueueOrThrow` (sprawdza istnienie) → `hasActiveContacts` → `softDelete`. Gdy między check a delete kolejka zostaje usunięta przez inną sesję, `softDelete` zwraca 0 → `EntityNotFoundException`. Test `shouldThrowWhenQueueNotFoundOnDelete` sprawdza tylko brak kolejki na etapie `findQueueOrThrow`. Brak testu dla przypadku gdy `softDelete` zwraca 0 (TOCTOU scenario).

**[QueueController.java:80] `TenantContext.getTenantId()` — brak null-check**

Każdy endpoint wywołuje `TenantContext.getTenantId()` bez sprawdzania null. `getTenantId()` rzuca `IllegalStateException` gdy kontekst nie jest ustawiony. `GlobalExceptionHandler` prawdopodobnie mapuje `IllegalStateException` na HTTP 500. Jest to poprawne zachowanie (brak TenantContext to błąd konfiguracji filtrów), ale warto to udokumentować. Wzorzec identyczny z innymi kontrolerami — nie jest nową regresją.

**[QueueResponse.java:53] Mutowalna lista w rekordzie DTO**

```java
queue.getRequiredSkills(),
```

`requiredSkills` to `ArrayList<String>` — mutowalny. Rekord `QueueResponse` przechowuje bezpośrednią referencję. Ktoś mający dostęp do encji mógłby modyfikować listę przez DTO. Rozważ `Collections.unmodifiableList(queue.getRequiredSkills())` lub `List.copyOf(queue.getRequiredSkills())`.

---

### Positive Observations

- **`QueueRepository` prawidłowo rozszerza `TenantAwareRepository`** i wywołuje `assertSameTenant()` w każdej metodzie write (`insert`, `update`, `softDelete`). Wzorzec multi-tenancy przestrzegany konsekwentnie.
- **`setTenantContextInDb(tenantId)` wywoływane jawnie** z przekazanym `tenantId` zamiast wersji bez argumentu — właściwy wybór dla repozytoriów gdzie TenantContext może być niedostępny (async, scheduled).
- **Weryfikacja istnienia przed deaktywacją** (`findQueueOrThrow` + `hasActiveContacts` + `softDelete`) — logika biznesowa poprawnie sprawdza aktualne kontakty w kolejce przed usunięciem.
- **`@Pattern` na `routingStrategy`** w obu DTO (`CreateQueueRequest`, `UpdateQueueRequest`) — walidacja na poziomie API spójna z ENUM w DB.
- **Paginacja `PagedResponse`** zwracana konsekwentnie z `first`, `last`, `totalElements`, `totalPages` — spójne z resztą API.
- **Testy z `@Nested` i `@DisplayName`** — czytelna struktura, Arrange-When-Then w każdym teście, weryfikacja braku wywołań przez `verify(..., never())`.
- **`/routing-strategies` przed `/{id}`** — świadomy komentarz o kolejności tras Spring MVC, unikający konfliktu z UUID path variable.

### Summary

Implementacja poprawnie stosuje wzorce multi-tenancy i zawiera solidną dokumentację. Trzy kwestie wymagają poprawy przed merge: ręczna serializacja JSON (`skillsToJson`) z lukami dla znaków kontrolnych, martwy kod `@PrePersist`/`@PreUpdate` i brak `@Size(max=255)` na polu `name` w DTO. Brak testów dla `updateQueue` to widoczna luka w pokryciu.

**Ocena: 3.5/5** — solidna podstawa z kilkoma istotnymi usterkami (bug serializacji JSON, walidacja DTO) które powinny być naprawione przed merge.

---

## Pozytywne aspekty

- **Właściwa obsługa partycjonowania PostgreSQL.** Zapis przez natywny INSERT z pełnym castowaniem typów, UPDATE z kluczem partycji `started_at` w WHERE — unika full-partition-scan. Dobrze udokumentowane.

- **`ContactRepository` prawidłowo rozszerza `TenantAwareRepository`** i wywołuje `setTenantContextInDb(tenantId)` + `assertSameTenant()` konsekwentnie we wszystkich metodach write. Wzorzec zachowany.

- **Izolacja AGENT prawidłowo zaimplementowana w `listContacts`.** `effectiveAgentId = isAgent ? userId : params.agentId()` poprawnie nadpisuje przekazany filtr, uniemożliwiając agentowi podanie `agentId` innego agenta w query params.

- **`MAX_PAGE_SIZE = 100` w `ContactService`** ogranicza maksymalny rozmiar strony, spójnie z innymi serwisami projektu.

- **`PagedResponse` z pełnymi metadanymi** (`totalElements`, `totalPages`, `first`, `last`) — spójne z `CustomerController` i nowszymi endpointami.

- **Walidacja DTO na `CreateContactRequest`** — `@NotBlank` + `@Pattern` na `channel` i `direction` z wyraźnymi enumerable wartościami. `DispositionRequest` ma `@NotBlank` + `@Size(max=50)`.

- **`@Operation` / `@ApiResponse` na wszystkich endpointach** — Swagger UI dokumentuje wszystkie kody odpowiedzi, łącznie z 409 dla naruszeń reguł biznesowych.

---

## Review: Twilio Recording Pipeline — 2026-04-01

### Pliki: `TwilioWebhookController.java`, `TwilioRecordingDownloadService.java`, `RecordingService.java`, `ContactRepository.java`

---

### Bugs / Critical Issues

**[TwilioWebhookController.java:506–529] `resolveContactIdFromConference` wywołuje synchroniczne Twilio REST API w wątku HTTP webhooka — ryzyko timeoutu i podwójnego 12100**

`Conference.fetcher(conferenceSid).fetch()` jest wywołaniem blokującym (HTTP do Twilio API) bezpośrednio w ścieżce obsługi webhooka. Recording callback jest oczekiwany przez Twilio, ale zdarzenie to nie wymaga odpowiedzi TwiML — Twilio oczekuje jedynie 2xx. Problemem jest czas: domyślny timeout Java SDK Twilio wynosi 30s. Jeśli Twilio API odpowie wolno (np. przeciążenie), wątek HTTP serwera jest blokowany przez cały ten czas. W środowisku produkcyjnym z wieloma równoczesnymi callbackami może to wyczerpać pulę wątków Tomcata i zablokować wszystkie webhooki, w tym połączenia głosowe (12100). Brak jakiegokolwiek timeoutu po stronie kodu.

Sugestia: Przenieść logikę `resolveContactIdFromConference` do metody `@Async` w `TwilioRecordingDownloadService`. Webhook powinien zwrócić 204 natychmiast i zlecić rozwiązanie contactId asynchronicznie — analogicznie do sposobu w jaki `recordingDownloadService.downloadAndStore()` jest zlecane bez blokowania.

---

**[TwilioWebhookController.java:450] `resolveContactIdFromConference` jest wywoływane wewnątrz bloku `try` z aktywnym `TenantContext`, ale nie wykonuje żadnych operacji DB — `TenantContext` jest ustawiany zbędnie przed Twilio API call**

`TenantContext.setTenantId(tenantId)` jest wywoływane w linii 440 przed blokiem `if (StringUtils.hasText(callSid))`. Gdy `callSid` jest null a `conferenceSid` jest dostępny, metoda `resolveContactIdFromConference` jest wywoływana (linia 450) z aktywnym `TenantContext`. Sama metoda nie używa `TenantContext`, ale wywołuje `Conference.fetcher().fetch()` — synchroniczne żądanie HTTP do Twilio. Gdyby metoda rzuciła `RuntimeException` inną niż `ApiException` lub `IllegalArgumentException` (np. `NullPointerException` wewnątrz SDK), exception propagowałby do bloku `catch (Exception e)` w linii 475, a `TenantContext.clear()` w bloku `finally` w linii 480 poprawnie go wyczyści. Ten aspekt jest bezpieczny, ale logika jest myląca — wygląda jakby `TenantContext` był potrzebny dla `resolveContactIdFromConference`, a tak nie jest.

---

**[TwilioRecordingDownloadService.java:133] `buildS3Key` ignoruje timestamp — klucz S3 generowany z `Instant.now()` zamiast czasu kontaktu**

`buildS3Key(tenantId, contactId)` (linia 248) deleguje do `recordingService.buildS3Key(tenantId, contactId, null)`. Gdy `timestamp == null`, `buildS3Key` używa `Instant.now()` (linia 330 w `RecordingService`). To oznacza, że klucz S3 dla nagrania konferencji Twilio jest generowany na podstawie czasu pobierania nagrania (po zakończeniu rozmowy + czas przetwarzania callbacku), a nie czasu rzeczywistego połączenia. Dla połączeń trwających przez północ (np. rozmowa zaczęta 23:58, callback o 00:05), nagranie trafi do folderu następnego miesiąca, podczas gdy rekord kontaktu wskazuje na poprzedni miesiąc. Powoduje to niezgodność między `recording_url` w DB a faktyczną lokalizacją w S3 tylko w edge-case'ach, ale nie powoduje utraty danych — S3 klucz jest zapisywany do DB po uploadzie.

Sugestia: `TwilioWebhookController` powinien przekazywać timestamp z `contactRepository.findById` (pobrać `startedAt` kontaktu) do `TwilioRecordingDownloadService.downloadAndStore`, a ten przekazywać do `buildS3Key`. Alternatywnie: pobierać kontakt w `downloadAndStoreSync` i używać `contact.getStartedAt()`.

---

**[TwilioRecordingDownloadService.java:141–143] Log `Files.size(tempFile)` po `uploadToS3` — plik może być już usunięty**

Sekwencja w `downloadAndStoreSync`:
1. `uploadToS3(s3Key, tempFile)` — sukces
2. `recordingService.saveRecordingUrlToContact(...)` — może rzucić wyjątek
3. `log.info("... size={}B", contactId, s3Key, Files.size(tempFile))` — log z rozmiarem

Blok `finally` usuwa `tempFile`. Jeśli `saveRecordingUrlToContact` rzuci wyjątek po kroku 2, exception propaguje do `catch` w `downloadAndStore` (linia 95), który loguje błąd. Następnie `finally` usuwa plik. Log z `Files.size(tempFile)` w kroku 3 może rzucić `IOException` jeśli plik jest już usunięty w środku innej sekwencji wywołań. W obecnym kodzie plik jest usuwany tylko w `finally` po zakończeniu `downloadAndStoreSync`, więc log w linii 141 jest osiągany tylko gdy upload i zapis do DB zakończą się sukcesem — plik jest wtedy jeszcze dostępny. Ale `Files.size()` może rzucić `IOException` jeśli plik jest niedostępny z innych powodów (np. antywirus, NFS). Ta `IOException` nie jest sprawdzana.

Sugestia: Zalogować rozmiar pliku zaraz po `downloadToTempFile` (kiedy plik jest świeżo pobrany), nie po uploadzie.

---

**[ContactRepository.java:297–315] `findContactIdByConferenceSid` jest zdefiniowana ale nigdy wywoływana z `handleRecordingCallback`**

Kontroler w `handleRecordingCallback` używa `resolveContactIdFromConference(conferenceSid)` (Twilio API call), a nie `contactRepository.findContactIdByConferenceSid(conferenceSid, tenantId)`. Tymczasem `findContactIdByConferenceSid` jest zaimplementowana i jej Javadoc opisuje dokładnie ten przypadek użycia (recording callback z ConferenceSid). Komentarz w kontrolerze (linia 444–445) explicite stwierdza, że `conference_sid` w `channel_metadata` jest "zawodny" i dlatego nie używa DB lookup. Jednak to twierdzenie jest wątpliwe — `updateConferenceSidInMetadata` jest wywoływana w `handleStatusCallback` gdy `ConferenceSid` jest obecny, więc dla typowego przebiegu konferencji `conference_sid` będzie w metadanych. Metoda DB lookup jest szybsza, tańsza i nie blokuje wątku HTTP.

Brak spójności między implementacją a deklarowaną semantyką tych dwóch metod — jedna z nich (DB lookup lub Twilio API) jest nadmiarowa w stosunku do faktycznego użycia.

Sugestia: Zmienić `handleRecordingCallback` na priorytetowe użycie `contactRepository.findContactIdByConferenceSid(conferenceSid, tenantId)` i dopiero przy braku wyniku (fallback) użyć `resolveContactIdFromConference`. Albo odwrotnie: jeśli DB lookup jest zawodny — usunąć `findContactIdByConferenceSid` jako martwy kod z odpowiednim komentarzem.

---

### Security Concerns

**[TwilioWebhookController.java:409–484] Brak weryfikacji podpisu HMAC `X-Twilio-Signature` na endpointach webhook — każdy może wysłać fałszywy recording callback**

Komentarz w Javadoc klasy (linia 40–41) wspomina o "opcjonalnej weryfikacji" przez `X-Twilio-Signature`. Żaden z endpointów (`/voice`, `/dtmf`, `/recording`, StatusCallback) nie weryfikuje podpisu. Endpoint `/recording` jest szczególnie narażony: fałszywy callback z dowolnym `recordingUrl` może spowodować, że serwis pobierze plik z atakującego serwera (SSRF). `TwilioRecordingDownloadService.downloadToTempFile` wykona HTTP GET na podany URL, uwierzytelniając się danymi Twilio Basic Auth — atakujący może spróbować przechwycić credentials (gdyby serwer atakującego odpowiedział przekierowaniem lub specjalnie skonstruowanym żądaniem).

Weryfikacja podpisu jest funkcją standardową SDK Twilio (`RequestValidator`). Brak jej implementacji to luka bezpieczeństwa w publicznym endpoincie produkcyjnym.

Sugestia: Dodać `@Component RequestValidator` (Twilio SDK) i walidować `X-Twilio-Signature` we wszystkich metodach webhook. Odrzucać żądania bez ważnego podpisu przez zwrot HTTP 403 lub 400. Twilio dokumentuje tę weryfikację jako obowiązkową dla produkcji.

---

**[TwilioWebhookController.java:506] `resolveContactIdFromConference` — brak walidacji formatu `conferenceSid` przed wywołaniem Twilio API**

`conferenceSid` pochodzi bezpośrednio z parametru POST bez żadnej walidacji formatu (powinien zaczynać się od `CF` i mieć 34 znaki). Twilio API zwróci błąd 404 dla nieprawidłowego SID, który jest obsługiwany przez `catch (ApiException e)`. Nie jest to bezpośrednia podatność (SDK obsługuje błąd), ale brak walidacji formatu oznacza, że dowolny string jest przesyłany do zewnętrznego API. Przy braku weryfikacji podpisu HMAC (patrz wyżej), atakujący może wymusić wiele niepotrzebnych wywołań Twilio API, co generuje koszty.

Sugestia: Dodać `pattern check` przed wywołaniem: `if (!conferenceSid.matches("CF[0-9a-f]{32}"))` → logować warning i zwrócić `Optional.empty()`.

---

### Architecture / Pattern Violations

**[RecordingService.java:199–207] `saveRecordingUrlToContact` wywołuje `TenantContext.setTenantId` bez poprzedniego `snapshot/restore` — niezgodność z konwencją async thread boundaries**

Architektura projektu wymaga dla wątków async wzorca `TenantContext.snapshot()` / `TenantContext.restore(snapshot)` / `TenantContext.clear()` w finally. Metoda `saveRecordingUrlToContact` jest wywoływana z wątku `@Async` (`cc-async-*`) i używa uproszczonego wzorca: `setTenantId` → `try/finally clear`. Wzorzec ten jest funkcjonalnie poprawny dla nowych wątków async (które startują z czystym TenantContext), jednak:

1. Narusza ustalony wzorzec projektu dokumentowany w CLAUDE.md — różni deweloperzy zobaczą dwa różne sposoby ustawiania kontekstu w async i mogą wybrać zły.
2. Metoda `processHangupEvent` w tym samym pliku (`RecordingService.java:168`) używa identycznego uproszczonego wzorca — komentarz dokumentuje to jako świadomy wybór.

Jest to spójne wewnętrznie, ale warto zdecydować o jednym standardzie. `snapshot/restore` jest semantycznie bardziej precyzyjne (restore ustawia poprzedni stan, a nie czyści), co jest ważne gdy metoda byłaby wywołana z wątku, który już ma TenantContext (np. w testach lub przy zagnieżdżonych async).

---

**[TwilioRecordingDownloadService.java:176–184] Nowy `HttpClient` tworzony dla każdego żądania — brak reużycia klienta HTTP**

`HttpClient.newBuilder().build()` tworzy nową instancję przy każdym wywołaniu `downloadToTempFile`. `HttpClient` jest ciężkim obiektem (zarządza pulą wątków, połączeń TCP, SSL session cache). Tworzenie nowej instancji dla każdego nagrania:
- marnuje zasoby (nowa pula wątków przy każdym pobraniu)
- uniemożliwia reużycie połączeń HTTP/1.1 keep-alive do Twilio
- przy dużej liczbie równoległych nagrań może prowadzić do wyczerpania deskryptorów plików

Sugestia: Przenieść `HttpClient` do pola `private final HttpClient httpClient` inicjalizowanego w konstruktorze lub `@PostConstruct`. `HttpClient` z Java 11+ jest thread-safe i może być współdzielony.

---

**[TwilioWebhookController.java:68] Brak `@ConditionalOnBean` na poziomie konstruktora dla `TwilioRecordingDownloadService`**

`TwilioWebhookController` jest oznaczony `@ConditionalOnBean(TwilioTelephonyAdapter.class)`. `TwilioRecordingDownloadService` jest oznaczony `@ConditionalOnProperty(name = "twilio.enabled", havingValue = "true")`. Oba warunkowe beany powinny być aktywne w tych samych warunkach, ale mechanizm aktywacji jest inny (bean vs property). Jeśli `TwilioTelephonyAdapter` zostanie aktywowany inaczej, może dojść do sytuacji gdzie kontroler jest aktywny ale serwis nie — Spring rzuci `NoSuchBeanDefinitionException` przy starcie. W praktyce oba są spójne (Twilio włączone = oba aktywne), ale warto ujednolicić warunek lub dodać do kontrolera dodatkowy `@ConditionalOnBean(TwilioRecordingDownloadService.class)`.

---

### Improvements & Suggestions

**[TwilioRecordingDownloadService.java:203] Nazwa pliku tymczasowego zawiera `recordingSid` — ryzyko path traversal przy nieprawidłowym SID**

`Files.createTempFile("twilio_rec_" + recordingSid + "_", ".mp3")` — `recordingSid` pochodzi z parametru HTTP webhooka i nie jest walidowany. Jeśli `recordingSid` zawiera `..` lub `/`, metoda `createTempFile` może zachować się nieprzewidywalnie zależnie od implementacji JVM. W praktyce `Files.createTempFile` tworzy plik w `java.io.tmpdir` i ignoruje separatory ścieżek w prefiksie (JDK sanityzuje), ale jest to defensywna luka w kodzie — lepsza byłaby sanityzacja przed użyciem w nazwie pliku.

Sugestia: Używać `recordingSid` po sanityzacji: `recordingSid.replaceAll("[^a-zA-Z0-9_-]", "_")` lub używać UUID jako nazwy pliku tymczasowego (ignorując SID).

---

**[ContactRepository.java:334] `updateConferenceSidInMetadata` nie wywołuje `assertSameTenant`**

Metoda `updateConferenceSidInMetadata` wykonuje natywny UPDATE przez `jdbcTemplate` i nie wywołuje `assertSameTenant(tenantId, contactId)` przed modyfikacją. Wzorzec projektu wymaga `assertSameTenant()` przed każdym write. RLS pozostaje aktywne (`setTenantContextInDb` jest wywoływane), ale brakuje guard na poziomie aplikacji — analogicznie do uwagi C3 z poprzedniego review, która była naprawiona w `clearRecordingUrl`.

Sugestia: Dodać `assertSameTenant(tenantId)` na początku `updateConferenceSidInMetadata`, przed `setTenantContextInDb`.

---

**[TwilioWebhookController.java:466–467] Sprawdzenie `.mp3` suffix przez `String.endsWith` — podatne na URL z query string**

```java
String twilioMp3Url = recordingUrl.endsWith(".mp3") ? recordingUrl : recordingUrl + ".mp3";
```

`recordingUrl` pochodzi bezpośrednio z parametru Twilio POST. Jeśli URL zawiera query string (np. `https://api.twilio.com/...?foo=bar`), warunek `endsWith(".mp3")` zwróci false i URL zostanie zmodyfikowany do `https://api.twilio.com/...?foo=bar.mp3` — nieprawidłowy URL który zwróci 404 lub błąd od Twilio. Twilio dokumentuje, że `RecordingUrl` nie zawiera rozszerzenia ani query string, więc jest to edge-case, ale defensywna walidacja powinna to obsłużyć.

Sugestia: Użyć parsowania URI: `URI.create(recordingUrl).getPath().endsWith(".mp3")` do sprawdzenia rozszerzenia, lub zawsze dopisywać `.mp3` i sprawdzić czy wcześniej nie było już dodane przez prefix path.

---

**[application.yml:276] Ngrok URL jako wartość domyślna `app.base-url` — ryzyko niezamierzonej konfiguracji produkcyjnej**

```yaml
base-url: ${APP_BASE_URL:https://rafaela-uncalumnious-refreshedly.ngrok-free.dev}
```

Konkretny ngrok URL hardcoded jako domyślna wartość. W środowisku CI/CD lub stagingowym gdzie `APP_BASE_URL` nie jest ustawione, aplikacja będzie używać tego URL do budowania TwiML action URL. Twilio wyśle żądania do tunelu ngrok dewelopera zamiast do właściwej instancji. Ten sam problem dotyczy `status-callback-url` w sekcji `twilio`.

Sugestia: Zmienić domyślną wartość na `http://localhost:8080` lub wymusić błąd startu gdy `APP_BASE_URL` nie jest ustawione w profilu prod (np. przez `@Value("${app.base-url}") @NotBlank`).

---

### Positive Observations

- **`TwilioRecordingDownloadService` poprawnie używa pliku tymczasowego zamiast buforowania w pamięci.** `Files.copy(bodyStream, tempFile)` ze streamingiem unika OOM dla dużych nagrań. Plik usuwany w `finally` niezależnie od sukcesu/błędu.
- **`@Async` w `downloadAndStore` z try/catch na całą metodę** — webhook zwraca 204 natychmiast, upload odbywa się w tle. Wyjątki logowane przez serwis, nie propagują do wątku HTTP.
- **`buildBasicAuthCredentials` waliduje obecność credentials** przed Base64 enkodowaniem — `IllegalStateException` zamiast cichego wysłania pustego nagłówka.
- **`handleRecordingCallback` zawiera prawidłowy fallback na `return noContent()`** gdy brakuje danych (status != completed, brak URL, brak tenantId, brak contactId) — nie rzuca wyjątków, Twilio nie ponawia callbacków przy 2xx.
- **`TenantContext.clear()` w `finally`** w `handleRecordingCallback` (linia 480) — kontekst czyszczony niezależnie od sukcesu lub błędu. Wzorzec przestrzegany konsekwentnie we wszystkich metodach kontrolera.
- **`resolveContactIdFromConference` łapie zarówno `ApiException` jak i `IllegalArgumentException`** oddzielnie — precyzyjna obsługa błędów Twilio API i błędów parsowania UUID z różnymi komunikatami logu.
- **Usunięcie `AND is_deleted = FALSE` z `ContactRepository`** — poprawna korekta: tabela `contact` nie posiada kolumny `is_deleted` (brak soft-delete na kontaktach zgodnie ze schemą).

### Summary

Implementacja pipline'u nagrań Twilio ma solidną strukturę async i poprawną izolację tenant w wątkach async. Krytycznym problemem jest synchroniczne wywołanie Twilio REST API w ścieżce webhooka HTTP (`resolveContactIdFromConference`) — może blokować wątki Tomcata i degradować system przy obciążeniu. Poważną luką bezpieczeństwa jest brak weryfikacji `X-Twilio-Signature` we wszystkich endpointach webhooka, co otwiera wektory SSRF i fałszywych callbacków. `findContactIdByConferenceSid` w `ContactRepository` jest martwym kodem — kontroler jej nie używa mimo że Javadoc opisuje dokładnie ten scenariusz. Nieużyty `HttpClient` tworzony per-request to antywzorzec wydajnościowy.

**Ocena: 3/5** — funkcjonalność działa, ale dwa istotne problemy (sync Twilio API call w webhook path, brak HMAC validation) wymagają naprawy przed deployem produkcyjnym.

- **Testy jednostkowe pokrywają kluczowe ścieżki** — graniczne przypadki dla AGENT vs SUPERVISOR, maksymalny rozmiar strony, brakujące kontakty, metadane paginacji. Podejście `@Nested` + `@DisplayName` poprawia czytelność.

- **Logowanie z kontekstem tenanta** — MDC jest już ustawiane przez `TenantFilter`. Logi w serwisie i repozytorium zawierają `tenantId` i `contactId` dla korelacji.

---

## Podsumowanie

**Ocena BE-027: ~~3.5/5~~ → 4.5/5** (po poprawkach 2026-03-20)

Implementacja Contact API solidna strukturalnie. Wszystkie krytyczne i ważne błędy naprawione: weryfikacja własności dla AGENT, stale L1 cache, brak `assertSameTenant`, `ObjectMapper` zamiast ręcznej serializacji, martwy `@PrePersist`/`@PreUpdate`, walidacja filtrów 400 zamiast 500. Otwarte jedynie sugestie S1 (whitelist disposition codes), S3 (test ON_HOLD), S4 (dokumentacja READ COMMITTED).

**Ocena ogólna backendu (po wszystkich poprawkach 2026-03-20): ~~3.5/5~~ → 4.5/5**

Naprawiono łącznie 18/20 uwag z poprzedniego review + wszystkie ważne z BE-027. Pozostałe otwarte: #9 (virtual threads risk — brak włączonego profilu, ryzyko przyszłe), #17 (circular dep `@Lazy` — zaakceptowane). Otwarte sugestie S1, S3, S4 z BE-027 nie blokują release.

---

## Review: BE-019 (Routing Engine) — 2026-03-21

### Pliki: `RoutingEngine.java`, `DefaultRoutingEngine.java`, `RoutingService.java`, `RoutingRequest.java`, `RoutingResult.java`, `AgentSessionData.java`, `ContactAssignedEvent.java`, `ContactQueuedMessage.java`, `AppUserRepository.java` (modyfikacja), `RabbitMQConfig.java` (modyfikacja), `DefaultRoutingEngineTest.java`, `RoutingServiceTest.java`

---

### Bugs / Critical Issues

**[RoutingService.java:77] `@Async` + `@Transactional` — transakcja otwierana w wątku async executor, ale `TenantContext` jest pusty**

`routeContact` jest annotowana `@Async` i `@Transactional`. Wywołanie z `onContactQueued` (wątek AMQP) deleguje wykonanie do puli wątków `cc-async-`. W wątku AMQP `TenantContext` **nigdy** nie jest ustawiany — jest to wątek infrastrukturalny, nie HTTP request thread. `TenantContext` nie używa snapshot/restore. W efekcie gdy `routeContact` wykona `queueRepository.findByIdAndTenantId(queueId, tenantId)`:
1. `TenantAwareRepository.setTenantContextInDb(tenantId)` wywoła `SELECT set_tenant_context(?)` — a dokładniej parametr pobiera z jawnie przekazanego `tenantId`, więc RLS jest ustawiane poprawnie.
2. Jednak `AppUserRepository` NIE rozszerza `TenantAwareRepository` — nie wywołuje `set_tenant_context()` i nie używa RLS. Zapytania `findByIdAndTenantIdAndDeletedFalse` i `countActiveContactsByAgentId` mają jawny filtr `tenantId` w WHERE — co jest prawidłowe.

Aktualnie nie ma wycieku danych z powodu explicite filtrowanych zapytań. Natomiast naruszony jest wzorzec architektoniczny: `@Async` bez `TenantContext.snapshot()/restore()/clear()`. Jeśli ktoś doda nową metodę w `routeContact` która korzysta z `TenantContext.getTenantId()` (np. dla logu MDC, audytu, WebSocket broadcast), rzuci `IllegalStateException` w runtime. `CrossTenantAspect.verifyTenantContext` loguje ERROR ale nie rzuca wyjątku — problemy będą trudne do zidentyfikowania.

Sugestia: przekazać `tenantId` do `routeContact` i na początku metody wywołać `TenantContext.restore(new TenantContext.Snapshot(tenantId, null, null))` z `finally { TenantContext.clear(); }`. Alternatywnie udokumentować w Javadoc jako świadomą decyzję z wylistowaniem wszystkich metod które NIE mogą używać `TenantContext` w tym flow.

---

**[RoutingService.java:116–118] Nieskończona pętla retry: `publishQueuedEvent` ponownie publikuje `contact.queued` — `onContactQueued` odbierze tę samą wiadomość**

```java
// routeContact gdy brak agentów:
publishQueuedEvent(contactId, queueId, tenantId);  // publikuje contact.queued

// onContactQueued odbiera contact.queued:
routeContact(event.contactId(), event.queueId(), event.tenantId());  // znów szuka agenta
// → znów brak agentów → znów publishQueuedEvent → nieskończona pętla
```

Gdy brak dostępnych agentów, `routeContact` publikuje `contact.queued` na exchange `cc.events`. Kolejka `cc.queue.contact-routing` jest zbindowana do tego exchange z routing key `contact.queued`. `onContactQueued` natychmiast odbierze wiadomość i ponownie wywoła `routeContact`. Przy wciąż braku agentów — pętla się powtarza bez żadnego limitu ani opóźnienia. `x-message-ttl: 30000ms` na kolejce ogranicza czas życia wiadomości, ale przez 30 sekund aplikacja będzie w tight loop przetwarzając kontakt w kółko, wykonując zapytania do Redis i bazy danych.

Jest to krytyczny błąd logiczny w architekturze retry. Wiadomość `contact.queued` powinna być publikowana wyłącznie przez oryginalnego nadawcę (np. `TelephonyEventPublisher` przy przychodzącym połączeniu), nie przez `RoutingService` sam do siebie.

Sugestia: usunąć `publishQueuedEvent` z gałęzi "brak agentów" w `routeContact`. Status kontaktu pozostaje `QUEUED` w bazie. Zewnętrzny mechanizm (np. scheduled job co N sekund lub event `agent.status.changed`) powinien wyzwalać ponowną próbę routingu dla kontaktów z statusem `QUEUED`.

---

**[DefaultRoutingEngine.java:289–298] SCAN po wszystkich kluczach `session:agent:*` — brak filtrowania po tenancie na poziomie Redis**

```java
ScanOptions options = ScanOptions.scanOptions()
        .match(AGENT_SESSION_KEY_PATTERN)  // "session:agent:*" — wszystkie tenanty
        .count(200)
        .build();
```

SCAN pobiera **wszystkie** klucze sesji agentów ze wszystkich tenantów, a filtrowanie po `tenantId` odbywa się dopiero w Javie (linia 308–310: `session.belongsToTenant(tenantId)`). W środowisku z 100 tenantami po 50 agentów każdy (5000 kluczy), routing dla jednego tenanta z 50 agentami skanuje 5000 kluczy tylko po to, by odrzucić 4950 z nich. Przy wielu równoległych routingach (np. 20 jednocześnie) generuje to 100000 odczytów Redis per sekunda.

Rozwiązanie z kluczem per-tenant: `session:agent:{tenantId}:*` lub zbiorem `SET` per-tenant (`session:agents:{tenantId}` jako Redis Set UUID agentów z kluczami `session:agent:{userId}` dla danych). SCAN z wzorcem `session:agent:{tenantId}:*` pozwoliłby na filtrowanie na poziomie Redis.

Uwaga: zmiana klucza wymaga aktualizacji `UserService` który zapisuje klucze sesji. Przy obecnej skali (dev/staging) nie jest to blokujące, ale powinno być zaplanowane przed skalowaniem.

---

### Security Concerns

**[DefaultRoutingEngine.java:137–141] Sticky agent: weryfikacja przynależności do tenanta opiera się wyłącznie na danych z Redis**

```java
if (!session.isAvailable() || !session.belongsToTenant(request.tenantId())) {
    return Optional.empty();
}
```

Dane w Redis mogą zostać zmodyfikowane przez inny komponent lub operację administracyjną, która niepoprawnie zapisze `tenantId`. W teorii (np. błąd w `UserService.updateStatus`) sesja agenta X z tenanta A mogłaby zawierać `tenantId` tenanta B. Silnik routingu zaakceptowałby takiego agenta dla tenanta B.

Obecna ochrona: `agentHasRequiredSkills` weryfikuje agenta przez `appUserRepository.findByIdAndTenantIdAndDeletedFalse(agentId, tenantId)` — to zapytanie do bazy z explicite podanym `tenantId` w WHERE. Więc weryfikacja przez DB jest wykonywana gdy jest wymagane dopasowanie skills. Gdy `requiredSkills` jest pusty i strategia jest inna niż SKILL_BASED, DB check nie jest wykonywany — jedyną ochroną jest wartość z Redis.

Sugestia: dla sticky agent zawsze wykonywać `appUserRepository.findByIdAndTenantIdAndDeletedFalse(agentId, tenantId)` niezależnie od skills — to jest już jednorazowy SELECT per routing, akceptowalny koszt dla bezpieczeństwa multi-tenant.

---

**[RoutingService.java:82–84] `EntityNotFoundException` wewnątrz `@Async` — nieobsługiwany wyjątek zależy od `AsyncUncaughtExceptionHandler`**

```java
Queue queue = queueRepository.findByIdAndTenantId(queueId, tenantId)
        .orElseThrow(() -> new EntityNotFoundException(...));
```

`routeContact` jest `@Async`. Gdy `EntityNotFoundException` zostanie rzucony (kolejka usunięta między publikacją eventu a jego przetworzeniem), wyjątek nie propaguje do wywołującego (wywołujący otrzymuje `CompletableFuture` z błędem). `AsyncConfig.getAsyncUncaughtExceptionHandler()` loguje błąd. Jednak `onContactQueued` wywołuje `routeContact(...)` bez oczekiwania na `Future` — zwrócona wartość jest zignorowana. Wyjątek z wątku async nie wróci do wątku AMQP, więc wiadomość **zostanie potwierdzona (ACK)** przez Spring AMQP, mimo że routing zakończył się błędem. Kontakt pozostanie w statusie `QUEUED` bez żadnej akcji naprawczej.

Jest to fundamentalny problem z `@Async` na `routeContact` wywoływanym z `@RabbitListener`: albo `routeContact` jest synchroniczny (blokuje wątek AMQP, ale błędy powodują NACK/retry), albo `@Async` wymaga jawnego obsłużenia `CompletableFuture` w listenerze.

Sugestia: usunąć `@Async` z `routeContact` — wątek AMQP jest dedykowany i blokowanie go przez czas routingu (kilka zapytań Redis + SQL) jest akceptowalne. Czas przetwarzania jest krótki (< 100ms przy małej liczbie agentów). Jeśli `@Async` jest wymagany, `onContactQueued` musi obsłużyć `CompletableFuture`:
```java
routeContact(...)
  .exceptionally(e -> { throw new RuntimeException("...", e); });
```

---

### Architecture / Pattern Violations

**[DefaultRoutingEngine.java:442–462] N+1 zapytań do bazy w `findAgentWithLeastActiveContacts` — zapytanie per agent w pętli**

```java
for (UUID agentId : sorted) {
    long count = appUserRepository.countActiveContactsByAgentId(agentId, tenantId);
    // ...
}
```

Przy 20 kwalifikowanych agentach — 20 zapytań SQL. Javadoc sam zauważa ten problem: _"dla małych list (< 50 agentów online) akceptowalne"_. Jednak przy strategii SKILL_BASED która jest domyślną dla kolejek specjalistycznych, ta ścieżka jest hot path. Każde wywołanie `routeContact` wykonuje do 50 zapytań.

Sugestia: batch query — jeden SELECT z GROUP BY:
```sql
SELECT agent_id, COUNT(*) FROM contact
WHERE agent_id IN (:agentIds)
  AND tenant_id = :tenantId
  AND status IN ('QUEUED', 'ACTIVE', 'ON_HOLD')
GROUP BY agent_id
```
Wynik jako `Map<UUID, Long>` w jednym zapytaniu. Dodać jako nową metodę `countActiveContactsByAgentIds(List<UUID> agentIds, UUID tenantId)` w `AppUserRepository`.

---

**[RoutingService.java:40] `RoutingService` nie rozszerza żadnego interfejsu domenowego — trudność testowania i rozszerzalności**

`DefaultRoutingEngine` ma interfejs `RoutingEngine` z adnotacją `@Primary`. `RoutingService` nie ma analogicznego interfejsu. Utrudnia to mockowanie w testach integracyjnych (gdzie nie chcemy uruchamiać rzeczywistego routingu) i zastąpienie implementacji w środowiskach enterprise. Obecne testy jednostkowe mockują `RoutingEngine` ale muszą tworzyć pełny `RoutingService`.

---

**[RabbitMQConfig.java:56] `QUEUE_CONTACT_ROUTING` zbindowany do `cc.events` z routing key `contact.queued` — ta sama kolejka publikuje i konsumuje**

`RoutingService.publishQueuedEvent` publikuje na `cc.events` z routing key `contact.queued`. Ta sama kolejka `cc.queue.contact-routing` jest zbindowana do `cc.events` z routing key `contact.queued`. Oznacza to, że `RoutingService` słucha na wiadomości, które sam produkuje (w scenariuszu "brak agentów"). Jest to oczekiwane tylko w scenariuszu retry — ale jak opisano w błędzie krytycznym #2, prowadzi to do nieskończonej pętli.

Dodatkowe ryzyko: jeśli inny komponent (np. `TelephonyEventPublisher`) opublikuje `contact.queued` z innym payload format niż `ContactQueuedMessage`, Jackson rzuci `MessageConversionException` podczas deserializacji w `onContactQueued` — wiadomość trafi do DLQ bez retry.

---

### Improvements & Suggestions

**[DefaultRoutingEngine.java:230–234] ROUND_ROBIN: `counter - 1` przy counter=0 (po fallback) zwraca ujemną wartość przed `Math.abs()`**

```java
Long counter = redisTemplate.opsForValue().increment(counterKey);
if (counter == null) {
    counter = 0L;
}
int index = (int) (Math.abs(counter - 1) % sorted.size());
```

Gdy `counter == null` fallbackuje do `0L`, wynik `Math.abs(0 - 1) % size = 1 % size`. Dla `size == 1` wynik to `0` (poprawny). Dla `size >= 2` wynik to `1`, co pomija pierwszego agenta i zawsze wybiera drugiego. Brak buga przy normalnym działaniu (Redis zwraca zawsze non-null dla INCR), ale wartość fallback `0L` daje mylący wynik.

Sugestia: fallback powinien zwrócić `1L` (pierwsze wywołanie INCR) lub użyć `counter != null ? counter : 1L`.

**[DefaultRoutingEngineTest.java:284] `lenient()` na `findByIdAndTenantIdAndDeletedFalse` w teście sticky — niejasny powód**

```java
lenient().when(appUserRepository.findByIdAndTenantIdAndDeletedFalse(AGENT_A, TENANT_ID))
        .thenReturn(Optional.of(buildAgent(AGENT_A, List.of("SALES"))));
```

`lenient()` jest używane selektywnie — w niektórych testach sticky bez widocznego powodu (test `shouldSelectPreferredAgentWhenAvailable`). Mockito strict stubs wymagałoby usunięcia stubu lub wyjaśnienia. Warto albo usunąć `lenient()` gdy stub jest faktycznie używany, albo dodać komentarz.

**[RoutingServiceTest.java:275–289] `onContactQueued` test nie weryfikuje, że `@Async` nie jest blokujące**

Test `shouldCallRouteContactForReceivedEvent` wywołuje `routingService.onContactQueued(message)` synchronicznie (bo `@Async` nie działa w testach jednostkowych bez Spring context). Test działa, ale nie weryfikuje zachowania asynchronicznego. Warto dodać komentarz informujący, że test ignoruje `@Async` i zachowanie w runtime jest inne.

**[ContactQueuedMessage.java] Brak `@JsonIgnoreProperties(ignoreUnknown = true)` — deserializacja wrażliwa na rozszerzenie modelu**

```java
public record ContactQueuedMessage(UUID contactId, UUID queueId, UUID tenantId) {}
```

Jeśli inny komponent opublikuje `contact.queued` z dodatkowymi polami (np. `priority`, `channel`), Jackson przy domyślnej konfiguracji rzuci `UnrecognizedPropertyException`. Dla wiadomości RabbitMQ zalecane jest `@JsonIgnoreProperties(ignoreUnknown = true)` — wiadomości są publicznym kontraktem i powinny być odporne na rozszerzenia.

---

### Positive Observations

- **Redis SCAN zamiast KEYS** — `connection.keyCommands().scan(ScanOptions)` z `count=200` jest poprawnym podejściem dla środowisk produkcyjnych. Iteratywny SCAN nie blokuje Redis event loop. Javadoc w kodzie i interfejsie explicite dokumentuje tę decyzję.
- **Izolacja multi-tenant w `AgentSessionData.belongsToTenant()`** — sprawdzenie `tenantId != null && tenantId.equals(this.tenantId)` chroni przed NPE. Filtrowanie po tenancie odbywa się przed zwróceniem listy kandydatów.
- **Deterministyczny wybór w ROUND_ROBIN i FIRST_AVAILABLE** — sortowanie po `UUID.toString()` zapewnia spójny wynik między instancjami aplikacji. Komentarz w Javadoc wyjaśnia dlaczego.
- **`@Primary` na `DefaultRoutingEngine`** — zgodnie z wzorcem `MockTelephonyAdapter`/`TelephonyAdapter`, umożliwia podpięcie alternatywnej implementacji bez modyfikacji konfiguracji.
- **`parseSessionData` obsługuje dwa formaty** (Map i String) z graceful fallback na null — defensywne programowanie dla danych zewnętrznych (Redis).
- **`contactRoutingQueue` z `x-message-ttl: 30000ms`** — ograniczenie czasu życia wiadomości routingu zapobiega przetwarzaniu przeterminowanych kontaktów. Dobra decyzja architektoniczna.
- **Testy jednostkowe z `@Nested`** — przejrzysta struktura, separacja testów per strategia, pomocnicze metody `stubScan` / `stubStickySession` / `stubAgentSkills` eliminują duplikację. Podejście spy na `scanAvailableAgents` jest uzasadnione i udokumentowane.
- **Javadoc na wszystkich klasach i metodach publicznych** — pełna dokumentacja intencji, kontraktu i ograniczeń.

---

### Summary

Implementacja ma solidną strukturę algorytmiczną i dobrą dokumentację, ale zawiera dwa krytyczne błędy architektoniczne: nieskończona pętla retry (`contact.queued` publikowany przez routing do samego siebie) oraz `@Async` + `@RabbitListener` bez obsługi `CompletableFuture` powodujący ciche ACK przy błędach. SCAN bez filtrowania per-tenant jest potencjalnym problemem wydajnościowym przy skali. Brak snapshot/restore TenantContext w wątku async narusza wzorzec architektoniczny projektu.

**Ocena: 3/5** — algorytm routingu poprawny, ale dwa krytyczne błędy architektoniczne muszą być naprawione przed merge: nieskończona pętla retry i `@Async`/AMQP bez obsługi Future.

## Review: EmailRoutingService.java, QueueRepository.java, Queue.java, EmailRoutingServiceTest.java — 2026-03-26

### Bugs / Critical Issues

**[EmailRoutingService.java:105] Split po przecinku nie obsługuje formatu RFC 5322 `"Name <email>"` — błędny adres przekazany do lookup**

```java
String primaryToAddress = message.getToAddress().split(",")[0].trim();
```

Gdy `to_address` zawiera RFC 5322-kompatybilne wartości jak `"Support Team <support@mycompany.com>"`, split po przecinku zwróci `"Support Team <support@mycompany.com>"` jako token (bez przecinka). Przekazanie tego do `findByEmailAddressAndTenantId` nigdy nie znajdzie kolejki, bo LOWER(`"Support Team <support@mycompany.com>"`) != LOWER(`"support@mycompany.com"`). Routing po adresie email cicho odpada i system przechodzi do fallbacku — bez loga wskazującego przyczynę.

Format ten jest powszechny: większość klientów email i serwerów SMTP (Gmail, Outlook) umieszcza adresy w formacie `Display Name <addr>` w nagłówku `To:`. Efekt: funkcja routingu po adresie kolejki nigdy nie zadziała w realistycznym środowisku produkcyjnym.

Sugestia — wyodrębnić adres z nawiasów ostrych przed przekazaniem do lookup:
```java
String raw = message.getToAddress().split(",")[0].trim();
String primaryToAddress = extractEmailAddress(raw);
// gdzie extractEmailAddress() stosuje regex: .*<(.+)>.*  lub zwraca raw gdy brak < >
```

**[QueueRepository.java:242–273] INSERT pominął nową kolumnę `email_address` — encja `Queue` ma pole `emailAddress`, ale INSERT go nie uwzględnia**

```java
em.createNativeQuery("""
    INSERT INTO queue (
        queue_id, tenant_id, name, routing_strategy,
        required_skills, sticky_agent_timeout_seconds,
        max_concurrent_contacts_per_agent, wait_config,
        is_active, created_at, updated_at
    ) VALUES (...)
    """)
```

Migracja V029 dodała kolumnę `email_address` do tabeli `queue`. Encja `Queue.emailAddress` istnieje (linia 96). Jednak natywny INSERT w `QueueRepository.insert()` nie zawiera `email_address` w liście kolumn ani wartości. Skutek: każda nowo tworzona kolejka zawsze ma `email_address = NULL`, nawet jeśli użytkownik podał adres w formularzu. Pole jest zapisywane w encji Java, ale nigdy nie trafia do bazy danych.

Natywny UPDATE w `QueueRepository.update()` (linia 301) ma ten sam błąd — brak `email_address = :emailAddress` w SET.

Sugestia — dodać do INSERT:
```sql
INSERT INTO queue (
    queue_id, tenant_id, name, routing_strategy,
    required_skills, sticky_agent_timeout_seconds,
    max_concurrent_contacts_per_agent, wait_config,
    email_address,   -- <-- dodać
    is_active, created_at, updated_at
) VALUES (
    ...,
    :emailAddress,   -- <-- dodać
    ...
)
```
I analogicznie w UPDATE: `email_address = :emailAddress,` przed `is_active`.

### Security Concerns

_None identified._

### Architecture / Pattern Violations

_None identified._

### Improvements & Suggestions

**[EmailRoutingService.java:166] `new ObjectMapper()` wewnątrz `matchesRule()` — tworzenie instancji per-wywołanie**

```java
com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
conditions = mapper.readValue(rule.getConditions(), ...);
```

`ObjectMapper` jest thread-safe i drogi w inicjalizacji (rejestracja modułów, konfiguracja serializacji). Tworzenie nowej instancji przy każdym wywołaniu `matchesRule()` — a ten jest wywoływany per reguła per wiadomość — jest zbędnym narzutem. `EmailRoutingService` już ma `@RequiredArgsConstructor`, co oznacza że `ObjectMapper` można wstrzyknąć jako dependency i użyć wspólnej instancji.

Sugestia — dodać `ObjectMapper` do pól klasy:
```java
private final ObjectMapper objectMapper;
```
I zastąpić `new ObjectMapper()` przez `objectMapper`.

**[EmailRoutingServiceTest.java] Brak testu dla formatu `"Name <email>"` w `to_address`**

Testy pokrywają:
- brak reguł + fallback po adresie (linia 237),
- wiele adresów rozdzielonych przecinkiem (linia 264),
- pierwszeństwo reguł nad fallbackiem (linia 289).

Brakuje testu dla `to_address = "Support Team <sales@mycompany.com>"` — formatu RFC 5322, który jest najpowszechniejszy w produkcji (jak opisano w błędzie krytycznym #1). Test powinien weryfikować, czy routing po adresie email działa z display-name w nagłówku.

**[QueueRepository.java:175–184] `findByEmailAddressAndTenantId` nie skorzysta z partial index — predykat LOWER() blokuje użycie indeksu**

```sql
AND LOWER(email_address) = LOWER(CAST(:emailAddress AS TEXT))
```

Partial index `idx_queue_email_address` jest zdefiniowany na `(tenant_id, email_address)`. PostgreSQL może użyć tego indeksu przy warunku `email_address = ...` (equality), ale `LOWER(email_address) = LOWER(...)` wymaga pełnego przeskanowania kolumn bo LOWER() nie jest indeksowane. Dla małej liczby kolejek per tenant nie jest to problem, ale przy większej skali lookup będzie wolniejszy.

Rozwiązanie — dodać funkcyjny indeks:
```sql
CREATE INDEX idx_queue_email_address_lower
    ON queue (tenant_id, LOWER(email_address))
    WHERE email_address IS NOT NULL;
```
I zmienić zapytanie na: `AND LOWER(email_address) = LOWER(:emailAddress::TEXT)` (bez CAST) lub bez LOWER w obu stronach jeśli adres jest już znormalizowany.

### Positive Observations

- **`assertSameTenant()` w `insert()` i `update()` przed `setTenantContextInDb()`** — kolejność jest prawidłowa: weryfikacja multi-tenancy zanim kontekst RLS zostanie aktywowany w bazie.
- **`findByEmailAddressAndTenantId` jako `@Transactional(readOnly = true)`** — poprawne dla operacji odczytu.
- **`QueueRepository extends TenantAwareRepository`** — zgodność z architekturą projektu.
- **Trójstopniowa logika fallbacku w `findMatchingQueue()`** — czytelna, liniowa sekwencja: reguły → adres email → kolejka domyślna. Dobrze udokumentowana w logu warning (linia 72–73) z enumeracją wszystkich kroków.
- **Obsługa `null` tenantConfig w `findMatchingQueue()`** — `tenantConfig != null ? tenantConfig.get(...) : null` chroni przed NPE gdy tenantConfig jest null (linia 115–117).
- **Testy: `verify(queueRepository, never()).findByEmailAddressAndTenantId(any(), any())`** — test `shouldPreferRulesOverEmailAddressFallback` weryfikuje nie tylko wynik, ale też że repozytorium NIE zostało odpytane gdy reguła wygrała. Wysoka jakość weryfikacji zachowania.
- **`buildRuleWithPriority` jako wspólna metoda pomocnicza** — eliminuje duplikację, czytelna kompozycja przez `buildRule()` delegujący do `buildRuleWithPriority()`.

### Summary

Logika routingu jest architektonicznie spójna i dobrze ustrukturyzowana, ale implementacja ma dwa krytyczne błędy funkcjonalne: (1) brak `email_address` w natywnych INSERT/UPDATE w repozytorium sprawia, że adres email kolejki nigdy nie jest zapisywany do bazy, co czyni całą funkcjonalność niedziałającą end-to-end; (2) split po przecinku bez obsługi formatu `"Name <email>"` sprawi, że routing po adresie nie zadziała dla typowego ruchu email z realnych klientów pocztowych.

**Ocena: 2.5/5** — architektura prawidłowa, testy przyzwoite (choć brakuje kluczowego edge case), ale dwa błędy krytyczne blokują całą funkcjonalność w produkcji.

---

## Review: BE-021 (Wait Time Estimation) — 2026-03-26

### Pliki: `WaitTimeEstimationService.java`, `QueueWaitUpdatePayload.java`, `QueueStatsResponse.java`, `ContactRepository.java` (nowe metody), `QueueController.java` (endpoint stats), `WaitTimeEstimationServiceTest.java`

---

### Bugs / Critical Issues

**[WaitTimeEstimationService.java:96] `findAllByOrderByNameAsc()` wczytuje WSZYSTKICH tenantów do pamięci, w tym zawieszonych i usuniętych — brak filtrowania po stronie DB**

```java
List<Tenant> activeTenants = tenantRepository.findAllByOrderByNameAsc().stream()
        .filter(t -> t.getStatus() == TenantStatus.ACTIVE)
        .toList();
```

`findAllByOrderByNameAsc()` wykonuje `SELECT * FROM tenant ORDER BY name` bez żadnego filtra. Wszystkie encje tenantów (ACTIVE, SUSPENDED, INACTIVE) są materializowane do JVM, a następnie większość jest natychmiast odrzucana przez `.filter()`. Przy 1000 tenantach, z których 100 jest aktywnych, serwis ładuje 10x więcej danych niż potrzebuje. Scheduled job działa co 30 sekund — przy każdym przebudzeniu wykonuje ten sam wasteful SELECT.

Dodatkowe ryzyko: `findAllByOrderByNameAsc()` jest zapytaniem Spring Data JPA bez `TenantContext` (zaplanowany wątek). Jeśli encja `Tenant` zawiera LAZY kolekcje lub pola JSONB, materializacja może być jeszcze droższa.

Sugestia: dodać do `TenantRepository` dedykowaną metodę:
```java
List<Tenant> findAllByStatusOrderByNameAsc(TenantStatus status);
```
i zastąpić wywołanie `tenantRepository.findAllByStatusOrderByNameAsc(TenantStatus.ACTIVE)`. Eliminuje to filtrowanie w Javie i redukuje transfer danych O(N) do O(aktywni).

---

**[WaitTimeEstimationService.java:244–253] `scanAgentSessions()` — klucze Redis zdekodowane jako `new String(cursor.next())` bez określenia charset — potencjalne zniekształcenie kluczy z non-ASCII**

```java
keys.add(new String(cursor.next()));
```

`new String(byte[])` używa domyślnego charset JVM (zazwyczaj UTF-8 na nowoczesnych JDKach, ale zależy od `file.encoding` / `stdout.encoding` / `-Dfile.encoding`). Klucze sesji agentów mają format `session:agent:{UUID}` — UUID zawiera tylko ASCII, więc w praktyce jest to bezpieczne. Ale wzorzec jest kruchy: jeśli kiedykolwiek klucze będą zawierać non-ASCII (np. tenant name wbudowany w klucz), dekodowanie może dawać różne wyniki na różnych JVM.

Sugestia: użyć `new String(cursor.next(), StandardCharsets.UTF_8)` dla explicite i przenośnej konwersji.

---

**[ContactRepository.java:873–886] `getAvgHandleTimeSeconds` — `NOW() - INTERVAL '7 days'` w zapytaniu nie pomija kontaktów z `is_deleted = true`**

```sql
SELECT COALESCE(
    AVG(EXTRACT(EPOCH FROM (ended_at - started_at))),
    300
)
FROM contact
WHERE tenant_id = CAST(:tenantId AS uuid)
  AND queue_id  = CAST(:queueId  AS uuid)
  AND started_at >= NOW() - INTERVAL '7 days'
  AND ended_at IS NOT NULL
```

Zapytanie nie filtruje `is_deleted = false`. Tabela `contact` stosuje soft-delete (`is_deleted` kolumna, zgodnie z architekturą projektu). Kontakty oznaczone jako usunięte powinny być wykluczone z obliczeń AVG handle time — ich `ended_at - started_at` może odzwierciedlać czas do usunięcia, nie faktyczny czas obsługi.

Sugestia: dodać `AND is_deleted = false` do obu zapytań (`getAvgHandleTimeSeconds` i `countWaitingByQueueId`). Dla `countWaitingByQueueId` jest to szczególnie ważne — kontakty usunięte ze statusem `QUEUED` (soft-deleted) zawyżałyby `waitingCount` i fałszowały EWT.

---

**[WaitTimeEstimationService.java:131–135] `processTenant` z hardkodowanym limitem paginacji 1000 — tenanci z ponad 1000 kolejkami nie dostaną broadcastu EWT dla wszystkich kolejek**

```java
List<Queue> queues = queueRepository.findAllByTenantId(tenantId, null, 0, 1000).content();
```

Komentarz `// zakładamy < 1000 kolejek` jest założeniem MVP, ale jest nieudokumentowanym silent truncation. Jeśli kiedykolwiek tenant będzie miał > 1000 kolejek (np. duże call center z kolejkami per kampania), serwis będzie broadcastował EWT tylko dla pierwszych 1000 kolejek bez żadnego loga WARNING ani alertu. Reszta kolejek będzie miała stale dane w UI supervisora.

Sugestia: dodać log WARNING gdy `queues.size() == 1000`:
```java
if (queues.size() >= 1000) {
    log.warn("[EWT] Tenant {} ma >= 1000 kolejek – paginacja może być niewystarczająca. " +
             "Rozważ zwiększenie limitu lub paginację EWT.", tenantId);
}
```
Docelowo zaimplementować paginację wewnątrz `processTenant` iterującą do wyczerpania stron.

---

**[QueueController.java:222–227] Rekonstrukcja encji `Queue` z DTO w kontrolerze — naruszenie separacji warstw i ryzyko utraty danych**

```java
com.contactcenter.domain.model.Queue queue = com.contactcenter.domain.model.Queue.builder()
        .queueId(queueResponse.queueId())
        .tenantId(queueResponse.tenantId())
        .name(queueResponse.name())
        .build();
```

Kontroler pobiera `QueueResponse` (DTO) z `queueService.getQueue()`, a następnie ręcznie buduje encję domenową `Queue` z podzbioru pól. Ta encja ma tylko 3 z ~10 pól ustawionych — `routingStrategy`, `requiredSkills`, `maxConcurrentContactsPerAgent` itd. są `null`. Encja jest przekazywana do `waitTimeEstimationService.getQueueStats()`, które używa tylko `queueId` i `name`, więc aktualnie nie rzuca NPE.

Problemy z tym wzorcem:
1. Jeśli `getQueueStats` zostanie rozszerzone o dostęp do `routingStrategy` lub innych pól, code review nie wykryje NPE — partial encja wygląda jak pełna.
2. Kontroler importuje domenową encję `Queue` przez FQCN (`com.contactcenter.domain.model.Queue`) — to obejście, nie wzorzec.
3. Poprawne podejście: przekazać `UUID queueId` i `UUID tenantId` do serwisu, który sam załaduje encję przez repozytorium.

Sugestia: zmienić sygnaturę `getQueueStats` na `getQueueStats(UUID tenantId, UUID queueId)`. Serwis ładuje kolejkę wewnętrznie przez `queueRepository.findByIdAndTenantId`. Eliminuje to redundantny podwójny load (kontroler robi `queueService.getQueue` który już ładuje Queue, a następnie buduje sztuczną encję).

---

### Security Concerns

**[WaitTimeEstimationService.java:316] `countAvailableAgents()` — weryfikacja cross-tenant opiera się na danych w Redis bez walidacji DB — analogiczny problem jak w `DefaultRoutingEngine`**

```java
String sessionTenantId = session.get("tenantId");
if (!tenantIdStr.equals(sessionTenantId)) continue;
```

Jak zidentyfikowano w poprzednim CR (BE-019, Security Concern #1 — `DefaultRoutingEngine.java:137`), weryfikacja przynależności agenta do tenanta opiera się wyłącznie na danych zapisanych w Redis. Jeśli `UserService.updateStatus` zapisze błędny `tenantId` w sesji (błąd kodu, race condition), agent z tenanta A może być liczony jako dostępny dla tenanta B.

W kontekście EWT skutek jest mniej krytyczny niż przy routingu (niepoprawna liczba agentów = niepoprawne EWT, ale brak wycieku danych). Jednak wzorzec jest ten sam co w BE-019 — oba serwisy (`DefaultRoutingEngine` i `WaitTimeEstimationService`) mają identyczny kod weryfikacji Redis-only. Jeśli zdecydujemy się dodać DB verification w jednym miejscu, należy to zrobić w obu.

Uwaga: dla scheduled job (co 30s) koszt DB verification per agent byłby zbyt wysoki — N agentów * M tenantów zapytań per batch. Rozwiązanie systemowe to namespace per tenant w Redis (`session:agent:{tenantId}:{userId}`), co eliminuje potrzebę cross-tenant filtrowania po stronie Java.

---

**[QueueController.java:201–231] Endpoint `GET /api/queues/{id}/stats` — brak `@PreAuthorize` na poziomie metody, ale klasa-poziom `hasAnyRole('SUPERVISOR', 'ADMIN')` — AGENT może uzyskać dostęp przez curl z własnym tokenem**

Klasa `QueueController` ma `@PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")` na poziomie klasy. Endpoint `/stats` nie ma własnej adnotacji `@PreAuthorize`. Agenci nie mają dostępu — jest to poprawne dla zarządzania kolejkami.

Jednak `QueueStatsResponse` ujawnia `availableAgentsCount` — potencjalnie wrażliwą informację operacyjną. Rozważyć, czy `estimatedWaitSeconds` i `waitingCount` powinny być dostępne dla Agentów (aby wiedzielli jak długo klient czeka) — jeśli tak, endpoint wymaga osobnej, bardziej permisywnej adnotacji z ograniczonym DTO.

To uwaga projektowa, nie luka bezpieczeństwa w obecnym kształcie (SUPERVISOR i ADMIN mają dostęp do tych informacji).

---

**[WaitTimeEstimationService.java:289–305] `getQueueStats()` wykonuje pełny Redis SCAN na żądanie HTTP — potencjalny wektor DoS**

`getQueueStats` jest wywoływana z endpointu REST `GET /api/queues/{id}/stats`. Każde wywołanie HTTP triggeruje pełny `scanAgentSessions()` — iteratywny SCAN całego Redis przez cursor. Przy 10 000 kluczy sesji (duże środowisko) to dziesiątki round-tripów do Redis per żądanie HTTP. Złośliwy lub naiwny klient może wywołać endpoint w pętli, generując ciągłe obciążenie Redis.

Sugestia: dodać Redis cache dla `getQueueStats` z krótkim TTL (np. 5–10 sekund) — wynik SCAN jest i tak przybliżony (agenci zmieniają status co kilka sekund). Alternatywnie użyć tego samego wyniku `scanAgentSessions()` co scheduled job (cache mapy agentów w pamięci, odświeżanej co 30s). Klucz cache: `cache:queue:stats:{queueId}` (zgodnie z istniejącą tabelą Redis namespaces w ARCHITECTURE).

---

### Architecture / Pattern Violations

**[ContactRepository.java:862–864 i 900–901] Brak `setTenantContextInDb()` i świadome pominięcie RLS — wymaga udokumentowanej zasady**

```java
// Nie wywołuje setTenantContextInDb() – metoda wywoływana z kontekstu
// scheduled job iterującego po tenantach. Filtr tenant_id zapewnia izolację.
```

Pominięcie RLS jest udokumentowane i uzasadnione (scheduled job bez TenantContext). Natomiast ta sama metoda `countWaitingByQueueId` i `getAvgHandleTimeSeconds` mogą być wywoływane z kontekstu HTTP (przez `getQueueStats` → endpoint REST), gdzie `TenantContext` jest ustawiony.

W kontekście HTTP brak `setTenantContextInDb(tenantId)` oznacza, że `app.current_tenant_id` w PostgreSQL nie jest ustawiane dla tej transakcji — RLS pozostaje nieaktywne dla tych zapytań. Izolacja jest zapewniona przez jawny `tenant_id` w WHERE (co jest poprawne), ale niespójne z resztą repozytorium gdzie `setTenantContextInDb` jest standardem.

To nie jest błąd (jawne `tenant_id` w WHERE wystarczy), ale warto ujednolicić wzorzec lub explicite udokumentować że te metody są "dual-use" (scheduled + HTTP) i świadomie omijają `setTenantContextInDb`.

---

**[WaitTimeEstimationService.java:92] `@Scheduled(fixedRate = 30_000)` — brak `@Async` i brak ochrony przed nakładaniem się wywołań**

`broadcastWaitTimeUpdates` nie ma `@Async` ani `@ScheduledLock` (np. ShedLock). `fixedRate` znaczy: "uruchom co 30 sekund od poprzedniego startu" — jeśli poprzednie wywołanie trwa > 30s (np. 10 tenantów, 200 kolejek każdy, Redis wolny), Spring uruchomi kolejne wywołanie w nowym wątku podczas gdy poprzednie nadal działa. Dwa gleichzeitige broadcasty dla tych samych kolejek w tym samym czasie.

W środowiskach wieloinstancyjnych (np. dwa pod-y w Kubernetes) oba wystartują scheduler i każdy z nich będzie broadcastował WebSocket eventy — supervisorzy dostaną zduplikowane eventy co 30s.

Sugestia krótkoterminowa: zmień `fixedRate` na `fixedDelay` — następny broadcast zaczyna 30s po zakończeniu poprzedniego, nie po jego starcie. Rozwiązuje nakładanie się na jednej instancji.

Sugestia długoterminowa: użyć ShedLock z Redis (`spring-integration-redis` lub `shedlock-provider-redis-spring`) dla distributed lock — tylko jedna instancja broadcastuje naraz.

---

**[WaitTimeEstimationService.java:163] `processQueue` i `getQueueStats` — dwa osobne wywołania DB (`countWaitingByQueueId` + `getAvgHandleTimeSeconds`) zamiast jednego zapytania**

Każda kolejka wymaga 2 zapytań do bazy danych. Przy 10 tenantach z 50 kolejkami każdy, scheduled job wykonuje 1000 zapytań do DB co 30 sekund. Oba zapytania dotyczą tej samej tabeli `contact` z tymi samymi filtrami `tenant_id` i `queue_id`.

Możliwa optymalizacja: jedno zapytanie zwracające obie wartości:
```sql
SELECT
    COUNT(*) FILTER (WHERE status = 'QUEUED') AS waiting_count,
    COALESCE(AVG(EXTRACT(EPOCH FROM (ended_at - started_at)))
        FILTER (WHERE started_at >= NOW() - INTERVAL '7 days' AND ended_at IS NOT NULL), 300)
        AS avg_handle_time
FROM contact
WHERE tenant_id = :tenantId AND queue_id = :queueId AND is_deleted = false
```
Redukcja liczby zapytań z 2N do N per batch. Przy obecnej skali (dev/staging) nie jest krytyczne, ale warto zaplanować przed skalowaniem.

---

### Improvements & Suggestions

**[QueueWaitUpdatePayload.java:31] `estimatedWaitSeconds` jako `int` z wartością `Integer.MAX_VALUE` — frontend musi obsłużyć magic number**

`Integer.MAX_VALUE` (2147483647) jako sentinel value dla "brak agentów" jest nieczytelne dla klientów API. Frontend JavaScript/TypeScript musi wiedzieć, że `2147483647` oznacza nieskończoność. Lepszym podejściem byłoby:
1. Pole `Integer estimatedWaitSeconds` (boxed, nullable) gdzie `null` = "nieokreślony", lub
2. Oddzielne pole `boolean agentsAvailable` + `int estimatedWaitSeconds` (0 gdy unavailable), lub
3. Jawna stała w kontrakcie API z komentarzem Swagger `@Schema(description = "...; -1 when no agents available")`.

Obecne rozwiązanie z `Integer.MAX_VALUE` jest udokumentowane w Javadoc rekordu, ale brak adnotacji `@Schema` w DTO powoduje że Swagger UI nie wyświetla tej semantyki.

Sugestia: dodać `@Schema(description = "Szacowany czas oczekiwania w sekundach. Wartość 2147483647 (Integer.MAX_VALUE) oznacza brak dostępnych agentów.")` do pola.

---

**[WaitTimeEstimationServiceTest.java:57] `@MockitoSettings(strictness = Strictness.LENIENT)` — jak w BE-020 i BE-019, maskuje nieużywane stuby**

Identyczny problem jak w poprzednich PR-ach. `LENIENT` wyłącza Mockito strict stubs i pozwala na definiowanie stubów których testy nigdy nie używają. W `setUp()` stub `when(redisTemplate.execute(...)).thenReturn(null)` — ustawiony dla wszystkich testów przez `@BeforeEach`, ale testy `calculateEwt` nie wywołują Redis w ogóle. Bez `LENIENT` Mockito zgłosiłoby "Unnecessary stubbing detected" i pomogło utrzymać czysty zestaw testów.

Sugestia: przesunąć stub `redisTemplate.execute` do `@BeforeEach` tylko w klasach `BroadcastTests`, `GetQueueStatsTests` — tam gdzie Redis jest faktycznie potrzebny. Przywrócić `STRICT_STUBS` na poziomie klasy.

---

**[WaitTimeEstimationServiceTest.java — brak testów] Brakujące przypadki testowe**

Następujące scenariusze nie są pokryte:
1. `calculateEwt` z `avgHandleTime = 0.0` — `ceil(waiting * 0.0 / agents) = 0`. Czy jest to poprawne zachowanie? Możliwy edge case gdy AVG handle time obliczone z kontaktów o zerowym czasie obsługi.
2. `scanAgentSessions()` — brak testu dla scenariusza gdy Redis rzuca wyjątek. Javadoc mówi o fallbacku na pustą mapę, ale brak testu weryfikującego to zachowanie.
3. `broadcastWaitTimeUpdates()` — brak testu weryfikującego izolację cross-tenant: że kontakty tenanta A nie wpływają na EWT tenanta B przy jednoczesnym przetwarzaniu.
4. `getQueueStats()` — test weryfikuje tylko scenariusz "brak agentów w Redis". Brak testu gdy Redis ma agentów AVAILABLE dla tenanta.

---

**[WaitTimeEstimationService.java:193–194] Log z `String.format` wewnątrz argumentów loggera — niepotrzebne formatowanie gdy poziom DEBUG jest wyłączony**

```java
log.debug("[EWT] Queue {}: waiting={}, agents={}, avgHT={}s, EWT={}s",
        queueId, waitingCount, availableAgents, String.format("%.1f", avgHandleTime),
        estimatedWaitSeconds == Integer.MAX_VALUE ? "∞" : estimatedWaitSeconds);
```

`String.format("%.1f", avgHandleTime)` jest ewaluowane zawsze — niezależnie od tego czy poziom DEBUG jest włączony. SLF4J lazy evaluation (przez `{}` placeholdery) działa tylko dla samych argumentów, ale jeśli argumentem jest wyrażenie które wymaga obliczenia (String.format, ternary), jest ono ewaluowane przed przekazaniem do loggera.

Sugestia: dodać guard `if (log.isDebugEnabled())` lub użyć SLF4J lambda API (SLF4J 2.0+):
```java
log.debug("[EWT] Queue {}: waiting={}, agents={}, avgHT={}s, EWT={}s",
        queueId, waitingCount, availableAgents,
        () -> String.format("%.1f", avgHandleTime),
        () -> estimatedWaitSeconds == Integer.MAX_VALUE ? "∞" : estimatedWaitSeconds);
```

---

### Positive Observations

- **Redis SCAN zamiast KEYS** — `scanAgentSessions()` używa cursor-based SCAN z `count(100)`, identycznie z poprawionym wzorcem z CR-019. Nie blokuje Redis event loop. Obsługa wyjątku z graceful fallback na pustą mapę i logiem WARNING.
- **Cross-tenant filtrowanie w `countAvailableAgents()`** — sprawdzenie `tenantIdStr.equals(sessionTenantId)` przed zliczaniem AVAILABLE agentów skutecznie izoluje dane per-tenant w pamięci Java po SCAN.
- **Jednorazowy SCAN dla wszystkich tenantów** — `agentSessions` jest skanowane raz w `broadcastWaitTimeUpdates()` i współdzielone przez wszystkich tenantów (linia 106). Eliminuje N skanowań Redis dla N tenantów. To ważna optymalizacja dobrze przemyślana.
- **EWT formula poprawna i dobrze udokumentowana** — `ceil(waitingCount / availableAgents * avgHandleTime)` z obsługą edge case (0 waiting → 0, 0 agents → MAX_VALUE, brak historii → 300s fallback). Dokumentacja w Javadoc i komentarzach spójna z implementacją.
- **Izolacja błędów per-kolejka i per-tenant** — `try/catch` w pętli `processTenant` (linia 148) i `broadcastWaitTimeUpdates` (linia 111) sprawia, że błąd jednej kolejki nie przerywa broadcastu dla pozostałych. Wzorzec resilience poprawnie zastosowany.
- **`ContactRepository extends TenantAwareRepository`** — nowe metody `countWaitingByQueueId` i `getAvgHandleTimeSeconds` nie łamią dziedziczenia architektonicznego. Brak `setTenantContextInDb` jest świadomą decyzją udokumentowaną w Javadoc.
- **`@Scheduled(fixedRate = 30_000)` udokumentowane w Javadoc** — komentarz wyjaśnia dlaczego nie ma `@Transactional`, że błąd per-tenant nie przerywa pętli, co jest cenną wskazówką dla przyszłych maintainerów.
- **Testy pokrywają kluczowe graniczne przypadki** — 6 testów `calculateEwt`, pozytywne i negatywne dla `countAvailableAgents`, weryfikacja cross-tenant guard, broadcast dla wielu kolejek. Podejście `@Nested` + `@DisplayName` zachowane spójnie z resztą projektu.

---

### Summary

Implementacja EWT jest architektonicznie spójna i demonstruje dobre decyzje projektowe (jednorazowy SCAN, izolacja błędów per-kolejka, fallback 300s). Trzy problemy wymagają uwagi przed merge: (1) brak `is_deleted = false` w SQL zawyża `waitingCount` dla soft-deleted kontaktów QUEUED, (2) rekonstrukcja encji `Queue` z DTO w kontrolerze to anty-wzorzec tworzący partial object podatny na NPE przy rozszerzeniu, (3) `GET /api/queues/{id}/stats` triggeruje pełny Redis SCAN per żądanie HTTP bez cache — wektor DoS. Pominięcie `findAllByStatusOrderByNameAsc` (wczytywanie wszystkich tenantów zamiast aktywnych) to niepotrzebny narzut przy każdym ticku schedulera. Problem z `fixedRate` (nakładanie się wywołań) i brak ShedLock (multi-instancja) to znany dług techniczny wymagający adresowania przed wdrożeniem produkcyjnym.

**Ocena: 3.5/5** — solidna logika biznesowa i dobra odporność na błędy, ale bug soft-delete w SQL i anty-wzorzec partial-entity w kontrolerze muszą być naprawione przed merge.

---

## Review: EPIC-24 Transfer połączenia — pliki backendowe — 2026-05-15

Scope: TransferTargetType, TransferRequest, TelephonyAdapter, TelephonyEventPublisher, MockTelephonyAdapter, TwilioTelephonyAdapter, TransferController, AgentCallController, TransferAgentResponse, TransferCallRequest, TransferQueueResponse, TransferAgentQueueRepository, TransferQueueStatsRepository, TransferService, ContactService (metody initiateTransfer + bridgeCalls).

---

## [KRYTYCZNE] secondCallId w bridge endpoint nie jest weryfikowany relative do tenanta

**Plik:** `ContactService.java` linia ~930–961 (metoda `bridgeCalls`)

**Problem:** Metoda weryfikuje własność `callId` (pierwsza noga), ale `secondCallId` jest przekazywany bezpośrednio do `telephonyAdapter.bridgeCalls(callId, secondCallId)` bez żadnej weryfikacji, że ta sesja należy do tego samego tenanta lub do tego samego agenta. `MockTelephonyAdapter.requireSession()` nie sprawdza tenant — pobiera sesję wyłącznie po kluczu z globalnej `ConcurrentHashMap`. Agent tenanta A mógłby wywołać `POST /api/telephony/calls/{własnyCallId}/bridge/{callIdInnegoTenanta}` i połączyć dwie nogi należące do różnych tenantów.

**Rekomendacja:** Przed wywołaniem adaptera zweryfikuj, że sesja pod `secondCallId` istnieje i należy do tego samego tenanta:
```java
CallSession secondSession = telephonyAdapter.getSession(secondCallId)
    .orElseThrow(() -> new EntityNotFoundException("Sesja drugiej nogi nie istnieje: " + secondCallId));
if (!tenantId.equals(secondSession.getTenantId())) {
    throw new CrossTenantAccessException(UUID.fromString(secondCallId), tenantId, secondSession.getTenantId());
}
```
Wymaga to dodania metody `getSession(String callId): Optional<CallSession>` do interfejsu `TelephonyAdapter`.

---

## [KRYTYCZNE] UnsupportedOperationException w TwilioAdapter przekłada się na HTTP 500

**Plik:** `TwilioTelephonyAdapter.java` — metoda `initiateTransfer`, case `AGENT, QUEUE`

**Problem:** `UnsupportedOperationException` rzucana przez `TwilioTelephonyAdapter.initiateTransfer()` dla `AGENT` i `QUEUE` nie jest obsługiwana przez `GlobalExceptionHandler` — nie ma tam dedykowanego handlera. Zostanie złapana przez ogólny fallback i zwrócona jako HTTP 500 z technicznym komunikatem. W środowisku produkcyjnym (Twilio), transfer do agenta lub kolejki zwróci 500 zamiast zrozumiałego 501/400.

**Rekomendacja:** Dwie opcje:

1. Dodać handler w `GlobalExceptionHandler`:
```java
@ExceptionHandler(UnsupportedOperationException.class)
public ResponseEntity<ProblemDetail> handleUnsupportedOp(UnsupportedOperationException ex, WebRequest req) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_IMPLEMENTED, ex.getMessage());
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(pd);
}
```
2. Zamiast `UnsupportedOperationException` rzucać dedykowany `TelephonyException` lub `FeatureNotSupportedException`, który jest już obsługiwany.

---

## [WAŻNE] Dead code — mapa `meta` budowana, lecz nigdy nie przekazywana do `contactEventService`

**Plik:** `ContactService.java` linie 864–884 (metoda `recordTransferEvent`)

**Problem:** `Map<String, Object> meta` jest tworzona i wypełniana (transfer_type, target_type, target_agent_id / target_queue_id), ale do `contactEventService.recordTransfer(...)` przekazywana jest jedynie zakodowana wartość `resolveTransferTarget(req)` i `req.transferType().name()`. Mapa `meta` nie jest nigdzie użyta — dead code wprowadzający mylące wrażenie, że metadane są zapisywane.

**Rekomendacja:** Usunąć mapę `meta` jeśli `contactEventService.recordTransfer` nie przyjmuje parametru metadata, albo rozszerzyć sygnaturę `recordTransfer` o `Map<String, Object> metadata` i faktycznie persystować te metadane. Metadane transferu (type, target) są przydatne do raportowania i historii.

---

## [WAŻNE] Brak filtra `is_deleted = FALSE` przy zliczaniu kontaktów QUEUED

**Plik:** `TransferQueueStatsRepository.java` linia 83–86 (metoda `countWaitingContactsByQueueIds`)

**Problem:** Zapytanie:
```sql
WHERE c.tenant_id = CAST(:tenantId AS uuid)
  AND c.queue_id  = ANY(CAST(:queueIds AS uuid[]))
  AND c.status    = 'QUEUED'
```
nie filtruje `c.is_deleted = FALSE`. Kontakty soft-deleted, które mają status `QUEUED`, zostaną wliczone do metryki `waitingContacts`. RLS na tabeli `contact` (V012) filtruje tylko po `tenant_id`, nie po `is_deleted`. Wynik: zawyżona liczba oczekujących kontaktów w `TransferQueueResponse`.

**Rekomendacja:**
```sql
WHERE c.tenant_id  = CAST(:tenantId AS uuid)
  AND c.queue_id   = ANY(CAST(:queueIds AS uuid[]))
  AND c.status     = 'QUEUED'
  AND c.is_deleted = FALSE
```

---

## [WAŻNE] `TransferService.getAvailableAgents` ładuje wszystkich agentów tenanta bez limitu

**Plik:** `TransferService.java` linia 75 (metoda `getAvailableAgents`)

**Problem:** `appUserRepository.findAllByTenantIdAndDeletedFalse(tenantId, Pageable.unpaged())` ładuje **wszystkich** nieusunętych użytkowników tenanta do pamięci bez żadnego limitu. Dla dużych call center (np. 500–1000 agentów) jest to nadmiarowe obciążenie pamięci przy każdym otwarciu panelu transferu przez każdego agenta. Filtrowanie po roli `AGENT`, statusie i `excludeUserId` odbywa się w Javie (stream), zamiast na poziomie zapytania SQL.

**Rekomendacja:** Dodać dedykowane zapytanie filtrujące na poziomie bazy:
```java
@Query("SELECT u FROM AppUser u WHERE u.tenantId = :tenantId AND u.deleted = false " +
       "AND u.role = 'AGENT' AND u.status != 'OFFLINE' AND u.id != :excludeId")
List<AppUser> findTransferCandidates(@Param("tenantId") UUID tenantId, @Param("excludeId") UUID excludeId);
```
Alternatywnie: cachowanie wyników na ~10–15 sekund w Redis z kluczem `transfer:agents:{tenantId}`.

---

## [WAŻNE] Brak walidacji formatu E.164 dla `phoneNumber`

**Plik:** `TransferRequest.java` linia 40–44 oraz `TransferCallRequest.java`

**Problem:** `TransferRequest.validate()` sprawdza jedynie czy `phoneNumber != null` dla `PHONE` transferów. Nie waliduje formatu E.164. Backend przekaże do adaptera dowolny string jako numer telefonu. Dodatkowo `TransferCallRequest` (DTO HTTP) nie ma żadnej adnotacji walidującej `phoneNumber`.

**Rekomendacja:** Dodać walidację w `TransferRequest.validate()`:
```java
case PHONE -> {
    Objects.requireNonNull(phoneNumber, "phoneNumber required for PHONE transfer");
    if (!phoneNumber.matches("^\\+[1-9]\\d{6,14}$")) {
        throw new IllegalArgumentException("phoneNumber must be in E.164 format: " + phoneNumber);
    }
}
```
Oraz w `TransferCallRequest`:
```java
@Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "phoneNumber must be in E.164 format")
String phoneNumber,
```

---

## [WAŻNE] `@Transactional` obejmuje wywołanie zewnętrznego adaptera telefonii

**Plik:** `ContactService.java` linia ~770 (metoda `initiateTransfer`)

**Problem:** Metoda jest oznaczona `@Transactional`. W tej samej transakcji wczytywany jest kontakt z bazy, wywoływany jest `telephonyAdapter.initiateTransfer()` (efekt uboczny poza bazą — wywołanie do Twilio lub operacja na sesji w pamięci) i zapisywane jest zdarzenie do historii. Jeśli transakcja zostanie wycofana z dowolnego powodu po wywołaniu adaptera, operacja telefoniczna jest nieodwracalna — rozbieżność między stanem DB a stanem telefonii.

**Rekomendacja:** Rozdzielić na dwie fazy:
- Faza 1 `@Transactional(readOnly=true)`: wczytanie i walidacja kontaktu.
- Faza 2 (bez `@Transactional`): wywołanie adaptera.
- Faza 3 `@Transactional`: zapis zdarzenia.

---

## [WAŻNE] Brak testów jednostkowych i integracyjnych dla nowego kodu

**Problem:** Zero testów dla `TransferRequest.validate()`, `TransferService`, `ContactService.initiateTransfer/bridgeCalls`, `TransferController`, `AgentCallController` (nowe endpointy), `MockTelephonyAdapter.initiateTransfer`. Krytyczne ścieżki multi-tenancy i walidacja nie są pokryte testami.

**Rekomendacja:** Jako minimum:
- Testy jednostkowe `TransferRequest.validate()` — wszystkie kombinacje targetType + transferType
- Testy `TransferService` z mockiem repozytoriów
- Test integracyjny `POST /api/telephony/calls/{callId}/transfer` z weryfikacją 403 dla innego agenta i cross-tenant

---

## [SUGESTIA] Brak stałych dla kluczy metadanych w `TelephonyEventPublisher`

**Plik:** `TelephonyEventPublisher.java` — nowe przeciążenie `publishTransferred(..., Map<String, String> metadata)`

**Problem:** Klucze mapy (`transfer_type`, `target_type`, `target_agent_id`, `target_queue_id`) są hardcoded jako string literały w `MockTelephonyAdapter` bez wspólnego kontraktu. Różne implementacje mogą użyć innych kluczy niekompatybilnie.

**Rekomendacja:** Zdefiniować stałe w `TelephonyEventPublisher`:
```java
public static final String META_TRANSFER_TYPE    = "transfer_type";
public static final String META_TARGET_TYPE      = "target_type";
public static final String META_TARGET_AGENT_ID  = "target_agent_id";
public static final String META_TARGET_QUEUE_ID  = "target_queue_id";
```

---

## [SUGESTIA] Brak `@Size` na `@PathVariable callId` i `secondCallId`

**Plik:** `AgentCallController.java` — endpointy `/{callId}/transfer` i `/{callId}/bridge/{secondCallId}`

**Rekomendacja:**
```java
@PathVariable @Size(max = 64) String callId,
@PathVariable @Size(max = 64) String secondCallId,
```

---

## Podsumowanie EPIC-24 Backend

**Ocena: 3/5** — Architektura jest solidna: N+1 rozwiązany przez zbiorowe SQL, `TransferRequest.validate()` eleganckie, dokumentacja Javadoc obszerna. Wykryto dwa problemy bezpieczeństwa (cross-tenant bridge, UnsupportedOperationException → 500) i kilka ważnych błędów poprawności (dead code meta, brak is_deleted, brak walidacji E.164, transakcja wokół zewnętrznego adaptera). Brak testów dla całego nowego kodu jest poważną luką.

**Najważniejsze do poprawy przed merge:**
1. Weryfikacja tenanta dla `secondCallId` w `bridgeCalls` — luka bezpieczeństwa
2. Obsługa `UnsupportedOperationException` w `GlobalExceptionHandler` — HTTP 500 w produkcji z Twilio
3. Usunięcie dead code mapy `meta` lub jej faktyczne użycie
4. Dodanie `AND c.is_deleted = FALSE` do `countWaitingContactsByQueueIds`
5. Walidacja formatu E.164 dla `phoneNumber`
6. Przynajmniej minimalne testy jednostkowe dla krytycznych ścieżek

---

## Review: EPIC-27 — CustomDisposition (domain + API + testy) — 2026-05-27

**Branch:** custom-dispozition
**Reviewer:** senior-code-reviewer agent
**Pliki:** `CustomDisposition.java`, `CustomDispositionRepository.java`, `CustomDispositionService.java`, `CustomDispositionController.java`, `ContactController.java` (available-dispositions endpoint), DTOs (4 pliki), `CustomDispositionServiceTest.java`

---

### [CRITICAL] `rows.get(0)` w `update()` — potencjalny `IndexOutOfBoundsException`

**Plik:** `CustomDispositionRepository.java:385`

**Problem:** Metoda `update()` wywołuje `rows.get(0)` bez sprawdzenia, czy lista jest niepusta. Serwis wywołuje `findByIdAndTenantId()` (sprawdza istnienie), a następnie `update()` — ale obie operacje są w osobnych transakcjach (serwis nie ma `@Transactional`). Przy współbieżnym usunięciu dyspozycji między `find` a `update`, zapytanie UPDATE...RETURNING zwróci 0 wierszy, a `rows.get(0)` rzuci `IndexOutOfBoundsException` → HTTP 500 zamiast 404.

**Sugestia:**
```java
if (rows.isEmpty()) {
    throw new ResourceNotFoundException("Dyspozycja nie istnieje lub została usunięta: " + d.getId());
}
CustomDisposition updated = mapRow(rows.get(0));
```
Alternatywnie: oznaczyć `CustomDispositionService.update()` jako `@Transactional`, żeby find i update były w jednej transakcji.

---

### [MAJOR] `@Pattern` na `tone` bez `@NotNull` — null przechodzi walidację i trafia do DB jako NULL

**Plik:** `CreateCustomDispositionRequest.java:17`, `UpdateCustomDispositionRequest.java:13`

**Problem:** `@Pattern(regexp = "positive|negative|neutral|warning")` w Jakarta Bean Validation domyślnie pozwala na `null` (adnotacja jest ignorowana gdy pole jest null). Brak `@NotNull` na polu `tone` oznacza, że JSON `{"tone": null}` przejdzie walidację bez błędu 400 i dotrze do DB, gdzie natrafi na `NOT NULL` constraint — skutkując HTTP 500 zamiast HTTP 400.

**Sugestia:**
```java
@NotNull @Pattern(regexp = "positive|negative|neutral|warning") String tone,
```
Analogicznie w `UpdateCustomDispositionRequest`.

---

### [MAJOR] `campaignId` / `queueId` z path parametru jest ignorowany w `updateForCampaign` i `deleteForCampaign`

**Plik:** `CustomDispositionController.java:140-152`, `CustomDispositionController.java:166-178`

**Problem:** Endpointy `PUT /campaigns/{campaignId}/{id}` i `DELETE /campaigns/{campaignId}/{id}` przyjmują `campaignId` jako path variable, ale go nie używają — serwis weryfikuje jedynie `id + tenantId`. Supervisor może więc wywołać `PUT /api/dispositions/campaigns/CAMPAIGN_X/{dispId}`, gdzie `{dispId}` należy do `CAMPAIGN_Y` (innej kampanii, ale tego samego tenanta), i operacja się powiedzie. To narusza semantyczny kontrakt URL i może prowadzić do nieintencjonalnych modyfikacji.

**Sugestia:** Dodać weryfikację zakresu w serwisie:
```java
public CustomDispositionDto updateForCampaign(UUID campaignId, UUID dispositionId, UpdateCustomDispositionRequest req, UUID tenantId) {
    CustomDisposition existing = customDispositionRepository.findByIdAndTenantId(dispositionId, tenantId)
            .orElseThrow(() -> new ResourceNotFoundException(...));
    if (!campaignId.equals(existing.getCampaignId())) {
        throw new ResourceNotFoundException("Dyspozycja nie należy do kampanii: " + campaignId);
    }
    // ... reszta update
}
```

---

### [MAJOR] N+1 round-trips w `resolveForContact` — podwójne zapytanie do DB

**Plik:** `CustomDispositionService.java:79-93`

**Problem:** Dla każdego wywołania `resolveForContact` z kampanią z dyspozycjami wykonywane są:
1. `setTenantContextInDb()` + `existsByCampaignId()` (2 round-trips)
2. `setTenantContextInDb()` + `findByCampaignId()` (2 round-trips)

Łącznie 4 round-trips, które można zredukować do 2. Dla każdego agenta kończącego kontakt to dodatkowe latency.

**Sugestia:** Dodać metodę `findByCampaignIdIfExists()` w repozytorium, która zwraca listę lub empty, i zastąpić pattern `exists + find` pojedynczym zapytaniem:
```java
List<CustomDisposition> campaignDisps = customDispositionRepository.findByCampaignId(campaignId, tenantId);
if (!campaignDisps.isEmpty()) {
    return campaignDisps.stream().map(this::mapToAvailable).toList();
}
```
Wtedy `existsByCampaignId` i `existsByQueueId` stają się zbędne dla flow resolucji.

---

### [MAJOR] Brak `@Transactional` w `update()` na poziomie serwisu — TOCTOU między find a update

**Plik:** `CustomDispositionService.java:223-238`

**Problem:** Metoda `update()` serwisu wykonuje dwie osobne operacje bazodanowe (`findByIdAndTenantId` + `update`) bez otaczającej transakcji. Brak `@Transactional` oznacza brak izolacji między odczytem a zapisem. W połączeniu z błędem opisanym wyżej (`rows.get(0)`) jest to potencjalne źródło HTTP 500 przy współbieżnych requestach.

**Sugestia:**
```java
@Transactional
public CustomDispositionDto update(UUID dispositionId, UpdateCustomDispositionRequest req, UUID tenantId) {
```

---

### [MINOR] Błędna numer migracji w JavaDoc encji i repozytorium

**Plik:** `CustomDisposition.java:17`, `CustomDispositionRepository.java:15`

**Problem:** JavaDoc obu klas odwołuje się do `(V092)`, podczas gdy faktyczna migracja to `V069__create_custom_disposition.sql`.

**Sugestia:** Poprawić referencje w JavaDoc na `V069`.

---

### [MINOR] Brak walidacji `@Min` dla `ordinal` — negatywne wartości przepuszczone

**Plik:** `CreateCustomDispositionRequest.java:18`, `UpdateCustomDispositionRequest.java:14`

**Problem:** Pole `ordinal` jest typem `int` bez żadnego ograniczenia zakresu. API akceptuje `ordinal: -999`, co może powodować nieoczekiwane sortowanie w interfejsie.

**Sugestia:**
```java
@Min(0) int ordinal
```

---

### [MINOR] Brak testu sukcesu dla `createForQueue` w `CustomDispositionServiceTest`

**Plik:** `CustomDispositionServiceTest.java:218-239`

**Problem:** Klasa `CreateForQueue` zawiera tylko test dla duplikatu kodu (rzuca ConflictException), ale brak symetrycznego testu ścieżki sukcesu (analogiczny do `createForCampaign_success_returnsDto`). Ryzyko: przyszła regresja w `createForQueue` nie zostanie wykryta.

**Sugestia:** Dodać test:
```java
@Test
@DisplayName("sukces → tworzy dyspozycję przypisaną do kolejki")
void createForQueue_success_returnsDto() { ... }
```

---

### [MINOR] `CustomDispositionService.resolveForContact` bez `@Transactional(readOnly = true)`

**Plik:** `CustomDispositionService.java:75`

**Problem:** Metoda wykonuje 2-4 zapytania bazodanowe bez otaczającej transakcji read-only. Choć nie powoduje błędów, brak `@Transactional(readOnly = true)` oznacza potencjalne niespójności odczytu między wywołaniami `exists` i `find` (niepowtarzalny odczyt) oraz brak optymalizacji Hibernate dla trybu read-only.

**Sugestia:**
```java
@Transactional(readOnly = true)
public List<AvailableDispositionDto> resolveForContact(UUID campaignId, UUID queueId, UUID tenantId) {
```

---

### Pozytywne obserwacje

- Multi-tenancy: wszystkie metody repozytorium poprawnie wywołują `setTenantContextInDb()` przed każdym zapytaniem i `assertSameTenant()` przed każdym zapisem — pełna zgodność z wzorcem projektu.
- `CustomDispositionRepository` poprawnie rozszerza `TenantAwareRepository`.
- Logika resolucji w `resolveForContact` jest czytelna, priorytet (kampania → kolejka → system) jest prawidłowy, a gwarancja niepustej listy jest efektywnie egzekwowana przez fallback `SYSTEM_DEFAULTS`.
- Walidacja Bean Validation na DTO jest w większości kompletna (`@NotBlank`, `@Size`, `@Pattern` na dispositionCode).
- `@PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")` na poziomie klasy kontrolera — poprawne; endpoint agenta (`/available-dispositions`) ma `hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')`.
- Wzorzec INSERT/UPDATE z `RETURNING` i natywnym SQL przez EntityManager jest spójny z resztą projektu.
- `SYSTEM_DEFAULTS` jako `static final List` — immutable, dobrze zdefiniowane.
- Testy jednostkowe mają dobrą strukturę `@Nested`, opisowe `@DisplayName` i używają AssertJ.

### Summary

Implementacja backendowa jest solidna architektonicznie (TenantAware, assertSameTenant, proper DTOs, clear resolution logic), ale ma dwie rzeczywiste usterki blokujące: potencjalny NPE/IndexOutOfBounds w update przy współbieżności (brakuje @Transactional i null-guard) oraz przepuszczenie null tone przez walidację (~500 zamiast 400). Pominięcie campaignId w update/delete to naruszenie semantyki URL.

**Ocena: 3/5** — wymaga naprawienia błędu walidacji tone i guard w `update()` przed mergem.
