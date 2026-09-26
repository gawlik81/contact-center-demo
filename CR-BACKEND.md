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

---

## Review: EPIC-27 — DispositionSet backend (V071, entities, repos, service, controller, DTOs, tests) — 2026-05-28

**Branch:** custom-dispozition

### Bugs / Critical Issues

- **DispositionSetService.java:281-289 / 321-329 (transakcja ulega rollback po złapanym wyjątku — `@Transactional` z `catch(Exception)`)** Metody `applyToCampaign` i `applyToQueue` są oznaczone `@Transactional`. Gdy `customDispositionRepository.insert(cd)` rzuca wyjątek (np. `DataIntegrityViolationException` z powodu duplikatu), Spring domyślnie oznacza transakcję do rollback po wyjściu z metody `@Transactional` — nawet jeśli wyjątek zostanie złapany wewnątrz (`catch(Exception e)`). Zachowanie to zależy od implementacji JPA/Hibernate: JPA może oznaczyć transakcję jako `rollback-only` po każdym wyjątku rzuconym przez EntityManager, co uniemożliwi COMMIT całej transakcji mimo złapania wyjątku. W praktyce dostaniesz `javax.persistence.RollbackException: Transaction marked as rollbackOnly` lub milczący rollback poprzednio wstawionych wierszy.
  - Fix: Wydziel insert pojedynczego elementu do osobnej metody oznaczonej `@Transactional(propagation = Propagation.REQUIRES_NEW)` — wewnętrzna transakcja upadnie niezależnie od zewnętrznej. Alternatywnie wykonuj try/catch dopiero w metodzie `insert` w repozytorium w osobnym `Propagation.REQUIRES_NEW`, lub użyj `@Transactional(noRollbackFor = DataIntegrityViolationException.class)` (mniej elastyczne).

- **DispositionSetService.java:271-278 / 311-318 (błędna semantyka ResourceNotFoundException dla pustego zestawu)** Gdy zestaw istnieje, ale jest pusty, metoda `applyToCampaign` rzuca `ResourceNotFoundException("Zestaw nie istnieje lub jest pusty")`. To zwraca HTTP 404, podczas gdy poprawna semantyka to 422 Unprocessable Entity lub 400 Bad Request (zasób istnieje, ale żądanie nie ma sensu). Komunikat błędu jest mylący — klient nie wie, czy zestaw naprawdę nie istnieje, czy jest tylko pusty.
  - Fix: Sprawdź istnienie zestawu przez `setRepo.findByIdAndTenantId` przed pobraniem elementów (analogicznie jak w `listItems`), rzuć `ResourceNotFoundException` jeśli zestaw nie istnieje, a dla pustego zestawu rzuć dedykowany `ValidationException` z HTTP 400 lub 422.

- **DispositionSetService.java:52-62 (N+1 queries w `listSets`)** Dla każdego zestawu z `findAllByTenantId` wykonywane jest osobne zapytanie `countBySetId`. Przy 50 zestawach = 51 zapytań do DB. Każde z nich wywołuje też `setTenantContextInDb` (dodatkowe `SELECT set_tenant_context(...)` per iteracja).
  - Fix: Zastąp całą pętlę jednym zapytaniem SQL: `SELECT s.*, COUNT(i.id) as item_count FROM disposition_set s LEFT JOIN disposition_set_item i ON i.set_id = s.id WHERE s.tenant_id = :tid GROUP BY s.id ORDER BY s.name`. To samo dotyczy `updateSet` (linia 137).

- **DispositionSetService.java:119-139 (TOCTOU race condition w `updateSet` — sprawdzenie nazwy bez blokady)** Metoda `updateSet` nie jest oznaczona `@Transactional`. Sekwencja: (1) `findByIdAndTenantId`, (2) `existsByNameAndTenantId`, (3) `setRepo.update` — to trzy osobne transakcje. Między krokiem 2 a 3 inny wątek może wstawić zestaw o tej samej nazwie. Baza danych UNIQUE constraint zapewni ostateczną spójność, ale wyjątek z bazy nie zostanie zmapowany do użytecznego HTTP 409.
  - Fix: Dodaj `@Transactional` na `updateSet` i obsłuż `DataIntegrityViolationException` z warstwy serwisowej, mapując go do `ConflictException`.

- **DispositionSetService.java:91-107 (analogiczny TOCTOU w `createSet`)** Identyczny problem: `existsByNameAndTenantId` bez `@Transactional` na `createSet`. UNIQUE constraint w bazie ochroni przed duplikatem, ale wyjątek nie trafi do czytelnego błędu 409.
  - Fix: Dodaj `@Transactional` na `createSet`.

### Security Concerns

- **DispositionSetService.java:282-288 (catch(Exception) — zbyt szerokie łapanie wyjątków)** Złapanie `Exception` zamiast `DataIntegrityViolationException` (lub bardziej specyficznego `org.postgresql.util.PSQLException`) ukrywa niespodziewane błędy (np. problemy z połączeniem, błędy mapowania) jako "pominięty duplikat". Takie błędy lądują w logach jako `WARN` zamiast `ERROR`, co utrudnia monitoring.
  - Fix: Złap `DataIntegrityViolationException` (Spring) lub sprawdzaj kod błędu PostgreSQL `23505` (unique_violation). Pozostałe wyjątki przepuść wyżej.

- **DispositionSetController.java (brak walidacji istnienia campaign/queue przed apply)** Endpointy `applyToCampaign` i `applyToQueue` przyjmują `campaignId`/`queueId` jako path variable bez sprawdzenia, czy te zasoby istnieją i należą do aktualnego tenanta. Wywołanie z UUID innej kampanii z innego tenanta jest zablokowane przez RLS w `customDispositionRepository.insert`, ale brak walidacji przynależności na poziomie serwisu to architektoniczne naruszenie zasady "fail fast".
  - Fix: Wstrzyknij odpowiednie repozytoria kampanii/kolejki do `DispositionSetService` i sprawdź przynależność do tenanta przed insertem.

### Architecture / Pattern Violations

- **DispositionSetService.java:270 i 310 (brak `@Transactional` na metodach CRUD — `createSet`, `updateSet`, `deleteSet`, `listSets`, `getSet`, `addItem`, `updateItem`, `removeItem`)** Tylko metody `applyToCampaign` i `applyToQueue` mają `@Transactional`. Pozostałe metody modyfikujące dane nie są transakcyjne na poziomie serwisu — transakcje są otwierane i zamykane w każdym wywołaniu repozytorium osobno. To łamie wzorzec "serwis jest granicą transakcji". Metody odczytu powinny mieć `@Transactional(readOnly = true)`.
  - Fix: Dodaj `@Transactional` na wszystkich metodach mutujących i `@Transactional(readOnly = true)` na metodach tylko odczytujących w serwisie.

- **CreateDispositionSetItemRequest.java:15 i UpdateDispositionSetItemRequest.java:13 (brak `@Min(0)` na polu `ordinal`)** Pole `ordinal` jest typem `int` bez żadnego ograniczenia, dopuszcza wartości ujemne (`-1`, `Integer.MIN_VALUE`). Baza danych nie ma CHECK constraint na `ordinal` w `disposition_set_item`.
  - Fix: Dodaj `@Min(0)` na polu `ordinal` w obu request DTO. Opcjonalnie dodaj CHECK constraint do migracji.

- **DispositionSetRepository.java:163-165 (em.detach przed assertSameTenant — zbędna operacja)** `em.detach(s)` jest wywoływany na encji `s` przed `setTenantContextInDb`. Ponieważ repozytorium używa natywnego SQL (nie merge/persist), `detach` jest zbędne — encja nigdy nie jest zarządzana przez EntityManager. To pozostałość skopiowana z innych repozytoriów używających `merge`.
  - Fix: Usuń wywołanie `em.detach(s)`.

- **DispositionSetItemRepository.java:200-202 (identyczny problem z em.detach)** To samo co powyżej.

### Improvements & Suggestions

- **DispositionSetService.java:92 (name uniqueness check — brak trim po stronie serwisu)** Request DTO `name` jest walidowane przez `@NotBlank`, ale trim wykonywany jest dopiero w kontrolerze frontendowym. Jeśli klient wyśle `"  TestSet  "` (spacje), `existsByNameAndTenantId` może zwrócić false, a UNIQUE constraint w bazie zadziała jako "fallback" z nieczytelnym błędem.
  - Fix: Wykonaj `req.name().trim()` przy porównaniu nazw i przy budowaniu encji — identycznie jak frontend robi `.trim()` w `submitSetForm()`.

- **DispositionSetService.java:362-365 (buildResultMessage — logika wynikowego komunikatu)** Komunikat wynikowy jest budowany ręcznie przez konkatenację Stringa po polsku, co utrudnia i18n oraz testowanie. Lepiej zwracać dane strukturalne (co już robi `ApplySetResponse`) i budować komunikat po stronie klienta.

- **DispositionSetServiceTest.java:189 (importowanie argThat z własnej metody wrappera)** Klasa testowa definiuje prywatną statyczną metodę `argThat` jako wrapper dla `Mockito.argThat`. To zbędne — można używać `org.mockito.Mockito.argThat` lub `org.mockito.ArgumentMatchers.argThat` bezpośrednio.

- **DispositionSetServiceTest.java (brak testów dla `updateSet`, `deleteSet`, `addItem`, `removeItem`, `updateItem`, `listSets`, `getSet`)** Testy pokrywają tylko `createSet` i `applyToCampaign/applyToQueue`. Brakuje testów dla reszty metod CRUD, co przy N+1 i TOCTOU problemach oznacza brak regresji.

### Positive Observations

- Oba repozytoria poprawnie rozszerzają `TenantAwareRepository` i wywołują `assertSameTenant` + `setTenantContextInDb` przed każdym zapisem.
- Metoda `update` w obu repozytoriach zwraca `Optional<T>` — poprawna obsługa race condition na concurrent delete.
- Metoda `delete` w obu repozytoriach zwraca `int` — serwis poprawnie sprawdza 0 i rzuca `ResourceNotFoundException`.
- Wszystkie DTO to rekordy Javy (`record`). DTOs są poprawnie oddzielone od encji i nie wyciekają przez API.
- `mapRow` używa bezpiecznego pattern matching typów (`instanceof Instant | Timestamp | OffsetDateTime`) z `IllegalArgumentException` jako fallback — defensywne i wyczerpujące.
- Kontroler poprawnie używa `@PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN')")` na poziomie klasy.
- Endpointy POST zwracają HTTP 201 z nagłówkiem `Location` — prawidłowa semantyka REST.
- Swagger annotations (`@Operation`, `@ApiResponse`) są kompletne i pokrywają wszystkie kody błędów.
- `DispositionSetItem` nie ma `created_at/updated_at` w encji — spójne z brakiem tych kolumn w DDL (co samo w sobie jest problemem, ale spójność encja-DDL jest zachowana).

### Summary

Kod backendowy jest dobrze zorganizowany architektonicznie: TenantAware, asercje bezpieczeństwa, właściwa separacja DTO/encja, kompletna dokumentacja Swagger. Dwa krytyczne problemy wymagają naprawy przed merge: (1) `@Transactional` z `catch(Exception)` w metodach apply może spowodować niekonsekwentny stan przy duplikatach (JPA `rollback-only`), (2) N+1 queries w `listSets` to problem skalowalności. Brakujące `@Transactional` na metodach CRUD serwisu to naruszenie wzorca projektowego.

**Ocena: 3/5** — wymaga naprawy transakcji w apply* i N+1 przed mergem.

---

## Review: BE-101 — `domain/plugin/runtime/*` (PluginRuntimeManager, PluginClassLoader, PlatformApiClassLoader, PluginContextImpl) — 2026-06-20

**Kontekst:** EPIC-28, RT-10 — jądro mechanizmu izolacji wykonania pluginów per tenant. Ticket explicit oznaczony jako "🔶 Zaimplementowane, NIE zamknięte — wymaga obowiązkowego code review przed merge". Ten review jest tym wymaganym gate.

**Plik(i) w zakresie:** `PlatformApiClassLoader.java`, `PluginClassLoader.java`, `PluginContextImpl.java`, `PluginRuntimeManager(Impl).java`, `PluginRegistry(Impl).java`, `PluginInstanceHandle.java`, `PluginActivationException.java`, `PluginHttpEgressClientImpl.java`, `PluginLoggerImpl.java`, `PluginConfigImpl.java`, `PluginBytecodeScanner.java` (BE-098, re-zweryfikowany w kontekście tego ticketu), oraz testy w `domain/plugin/runtime/*Test.java`.

### 🐛 Bugs / Critical Issues

- **`PluginRuntimeManagerImpl.java:199-212` (`downloadJarToLocalCache`) — wyciek plików tymczasowych, potwierdzone.** Każde wywołanie `load()` woła `Files.createTempFile("plugin-runtime-", ".jar")` i zapisuje bajty JAR-a, ale ten plik **nigdy nie jest usuwany** — nie w `load()` po skonstruowaniu `PluginClassLoader` (który trzyma URL do tego pliku przez cały czas życia instalacji — więc nie można usunąć go natychmiast), i nie w `unload()` (`closeQuietly(handle.classLoader())` zamyka `URLClassLoader`, ale nie usuwa pliku na dysku). Przy realnym użyciu (wielokrotne `enable/disable`, restarty, rollback między wersjami) to jest gwarantowany, nieograniczony wzrost zużycia dysku w `java.io.tmpdir` węzła — w produkcji to prowadzi do zapełnienia dysku i awarii całego JVM (nie tylko pluginów), czyli ironicznie pluginowy bug-do-naprawy sam staje się wektorem DoS na cały node.
  - **Severity: High** (nie Critical, bo nie jest to cross-tenant leak, ale jest to gwarantowany, samoistny problem operacyjny bez żadnego mitygującego mechanizmu w kodzie).
  - Fix: w `unload()`, po `closeQuietly(classLoader)`, usunąć plik JAR-a powiązany z tą instalacją — wymaga przechowania `Path` w `PluginInstanceHandle` (dodatkowe pole) albo rzutowania `classLoader` na `URLClassLoader` i odczytania `getURLs()[0]` do wyznaczenia ścieżki. Alternatywnie: cache JAR-ów po `pluginVersionId` (współdzielony między instalacjami tej samej wersji) z licznikiem referencji, czyszczony gdy licznik spada do 0 — unika też redundantnego pobierania z S3 dla każdej instalacji tej samej wersji pluginu.

- **`PluginRuntimeManagerImpl` / `lifecycleExecutor` (linie 68-73) — Thread-Context ClassLoader (TCCL) nie jest resetowany przed wywołaniem kodu pluginu — realna ścieżka ucieczki z izolacji, nie tylko teoretyczna.** `Executors.newCachedThreadPool` z custom `ThreadFactory` tworzy nowe wątki (`new Thread(runnable, "plugin-lifecycle-callback")`) bez wywołania `thread.setContextClassLoader(...)`. Zweryfikowałem empirycznie (mini-program testowy): nowo utworzony wątek **dziedziczy TCCL od wątku, który go stworzył** — w tym wypadku classloader aplikacji Springa (pełny classpath, w tym `domain.*`, repozytoria, Spring beans). `entryPoint.onActivate(context)` (linia 138) jest wywoływane na tym wątku **bez ustawienia TCCL na `PluginClassLoader` instalacji**. Każdy kod pluginu, który zawoła `Thread.currentThread().getContextClassLoader()` (wzorzec powszechny w bibliotekach Java — `ServiceLoader.load(X.class)` bez explicit classloadera, JAXB, niektóre biblioteki JSON/XML, frameworki DI) dostaje **pełny classloader aplikacji**, nie wąski `PlatformApiClassLoader`. Z tym classloaderem w ręku, `Class.forName("com.contactcenter.domain.tenant.TenantServiceImpl", true, tccl)` **działa** — omija całkowicie kontrolę zaimplementowaną w `PlatformApiClassLoader.loadClass()`, bo ta kontrola jest tylko na ścieżce explicit `PluginClassLoader`→`PlatformApiClassLoader`, nigdy na ścieżce TCCL.
  - To podkopuje bezpośrednio kryterium akceptacji #1 BE-101 ("parent classloader nie eksponuje pakietów app") — testy istniejące (`PlatformApiClassLoaderTest`, `PluginClassLoaderTest`) weryfikują tylko `Class.forName(name, true, PlatformApiClassLoader.INSTANCE)` lub `Class.forName(name, true, pluginClassLoaderInstance)` — czyli explicit classloader podany przez test, **nie** ścieżkę przez TCCL, którą realny plugin najprędzej by użył (albo świadomie jako atak, albo przypadkowo przez bibliotekę trzecią używaną wewnątrz pluginu).
  - ARCHITECTURE.md RT-10 wymienia "classloader manipulation to reach sibling classloaders via thread-context classloader swapping" jako **accepted residual risk** — ale opisuje mitygację jako "layered, not absolute": ASM scan + narrow parent classloader + manual review. TCCL nie jest pokryty przez ASM scan (`PluginBytecodeScanner.BLOCKED_METHOD_CALLS`/`BLOCKED_OWNER_PREFIXES` nie blokują `Thread#getContextClassLoader`/`Thread#setContextClassLoader`/`ServiceLoader#load`), więc obecny stan to nie "zaakceptowane ryzyko z mitygacją", to **brak mitygacji w warstwie, gdzie była explicit zaplanowana** (ASM blacklist) i **brak najprostszej, praktycznie darmowej mitygacji w warstwie executora** (ustawienie TCCL na granicy wywołania).
  - **Severity: Critical.** To jest dokładnie scenariusz z RT-10 ("malicious plugin uses reflection/classloader manipulation to reach... platform internals"), zrealizowany przez kod hosta, nie tylko teoretyczny JDK gap.
  - Fix (dwie warstwy, obie tanie, zrobić obie): (1) w `invokeWithTimeout`/wszędzie gdzie `entryPoint.onXxx(...)` jest wywoływane (tu i w przyszłym `PluginInvocationExecutor`, BE-102), opakować wywołanie: zapisać `ClassLoader previous = Thread.currentThread().getContextClassLoader()`, ustawić `Thread.currentThread().setContextClassLoader(classLoader)` (the `PluginClassLoader` tej instalacji) przed `entryPoint.onActivate(...)`, i przywrócić `previous` w `finally` — analogicznie do wzorca `TenantContext.snapshot/restore/clear` już wymaganego przez CLAUDE.md/§11.8, tylko dla TCCL. (2) Dodać do `PluginBytecodeScanner.BLOCKED_METHOD_CALLS`: `java/lang/Thread#getContextClassLoader`, `java/lang/Thread#setContextClassLoader`, oraz do `BLOCKED_OWNER_PREFIXES`: `java/util/ServiceLoader` — żeby plugin nie mógł nawet próbować, niezależnie od mitygacji (1).

### ⚠️ Security Concerns

- **`PluginLoggerImpl.java:30-38` — kontrakt SDK naruszony: logi pluginu trafiają do logów aplikacji, nie do osobnego sinka.** Javadoc interfejsu `PluginLogger` (plugin-sdk) deklaruje explicit: "messages written here are captured by the host... **never mixed into the platform's own application logs**" — gwarancja kluczowa dla operatora platformy (żeby złośliwy/hałaśliwy plugin nie mógł zaśmiecić/zalogflood'ować logów produkcyjnych używanych do diagnostyki). Implementacja robi `log.info/warn/error(...)` przez SLF4J wspólny z resztą aplikacji — czyli dokładnie to, co kontrakt zabrania. Javadoc klasy uczciwie to nazywa "tymczasowym sinkiem do BE-102", ale ticket jest oznaczony do code review **teraz**, więc warto to wytknąć zanim trafi do brancha jako established pattern.
  - Dodatkowy, bardziej palący problem: `message` (parametr `String`) pochodzi od pluginu i jest interpolowany w SLF4J bez żadnej sanityzacji ani limitu długości — plugin mógłby wstrzyknąć CRLF (log forging — sfałszować dodatkowe "linie logu" wyglądające jak inne zdarzenia) albo wysłać bardzo długi string (memory/IO pressure na logger), bez throttlingu czy circuit breakera (ten ostatni jest explicit zakresem BE-102, ale brak go teraz to brak izolacji error-logowania na granicy, gdzie inne mechanizmy fault containment już istnieją, np. timeout na onActivate).
  - **Severity: Medium** — nie cross-tenant leak, ale narusza explicit udokumentowaną gwarancję SDK i koliduje z celem "plugin nie może destabilizować platformy" (ARCHITECTURE.md §11.7) w wymiarze observability/log noise.
  - Fix: minimalnie, przed mergem do BE-102: ograniczyć długość `message` (np. truncate na 2000 znaków) i przefiltrować/escape'ować znaki kontrolne przy zapisie do SLF4J. Pełna naprawa (zapis do `plugin_invocation_log`) jest poprawnie zaplanowana na BE-102 — ale do tego czasu sugeruję dodać jawny `TODO(BE-102)` w kodzie (jest w Javadoc, ale nie jako code comment przy samej metodzie) i upewnić się, że BE-102 nie zostanie przypadkowo zamknięty bez przeniesienia tej logiki.

- **`PluginHttpEgressClientImpl.java:48-58` — nowy `HttpClient` tworzony per `PluginContextImpl` (czyli per invocation `onActivate`/przyszłe per-call w BE-102), nie cache'owany/współdzielony.** Każdy `java.net.http.HttpClient` tworzy własny connection pool i (w niektórych konfiguracjach) własny selector/event-loop thread — tworzenie nowego klienta per wywołanie to niepotrzebny narzut zasobów, i w przyszłym BE-102 (gdzie `publishPreContactConnect` będzie wołane per-contact, potencjalnie setki/tysiące razy na minutę) to się skaluje źle. Nie jest to security bug per se, ale koresponduje z deklarowanym w SDK Javadoc "platform-wide circuit breaker per (tenant_id, plugin_key, host)" — circuit breaker per (tenant, plugin, host) implikuje stan długowieczny, niezgodny z tworzeniem nowego `HttpClient` (i nowego `allowedHosts` Set) przy każdej konstrukcji `PluginContextImpl`.
  - **Severity: Low** dla BE-101 samego (zakres tego ticketu explicit wyklucza circuit breaker), ale flaguję żeby BE-102 nie odkrył tego jako niespodzianki — `HttpEgressClient`/jego stan circuit-breakera powinien żyć poza `PluginContextImpl` (np. cache'owany per `(tenantId, pluginKey)` w `PluginInstanceHandle` lub osobnym rejestrze), nie być tworzony na nowo przy każdej per-invocation instancji kontekstu.

### 🏗️ Architecture / Pattern Violations

- **`PluginRuntimeManager.java` Javadoc (linie ~30-38) / `load(UUID tenantId, ...)` — kontrakt "tenantId musi być zgodny z TenantContext.getTenantId() wątku wywołującego... nie weryfikowane wewnątrz tej metody" jest słabszy niż wzorzec ustalony przez resztę projektu.** W całym projekcie (CLAUDE.md, wzorce w `domain.customer`/`domain.contact`) tenant-aware serwisy *przyjmują* `tenantId` jako parametr od wołającego (to jest ustalony wzorzec — serwis nie czyta `TenantContext` sam, dostaje wartość). W tym sensie `PluginRuntimeManagerImpl` jest konsystentny. Zwracam jednak uwagę, że nazwa parametru i komentarz w Javadoc explicit przyznają, że nie ma **żadnej asercji** (`assertSameTenant`-podobnej) wewnątrz `load()` łączącej `tenantId` z czymkolwiek — w przeciwieństwie do np. `ContactRepository.updateNotes` (`assertSameTenant(tenantId)` explicit). Tu nie ma nic do zaasercjonowania przeciw (nie ma encji z `tenantId` do porównania na tym etapie przed `findInstallation`), ale `findInstallation(tenantId, installationId)` samo w sobie już filtruje po `tenant_id` w SQL (`TenantPluginInstallationRepository:54-55`), więc efektywnie **jest** poprawnie strzeżone — to jest tylko obserwacja, nie blokujący problem, bo gdyby wołający przekazał `tenantId` inny niż instalacja faktycznie posiada, `findInstallation` zwróci `Optional.empty()` i `load()` rzuci `ResourceNotFoundException` — poprawne zachowanie. Nie wymagam zmiany.

- **`PluginContextImpl.java` — brak limitu rozmiaru notatki w `appendContactNote` (linie ~131-141).** `Contact.notes` jest kolumną tekstową bez udokumentowanego limitu w tym kodzie — plugin mógłby wielokrotnie wołać `appendContactNote` z dużymi stringami, nieograniczenie zwiększając rozmiar wiersza `contact` (i obciążając replikację/backupy). Nie jest to RT-10 (cross-tenant), ale jest to brak resource quota analogiczny do tych opisanych w §11.7 jako "best-effort, tracked as residual risk" — sugeruję rozważyć limit długości (np. ucinanie na X znaków) przy zapisie, skoro `updateCustomerFields` już ma analogiczną dyscyplinę namespacingu. Nie blokujące dla BE-101 (limit najlepiej wprowadzić raz, dla wszystkich metod zapisu `PluginContext`, w BE-102 razem z circuit breakerem), ale warto zanotować jako follow-up.

### 🔧 Improvements & Suggestions

- **`PlatformApiClassLoader.java:79-111` (`loadClass`) — bardzo dobra, dobrze udokumentowana implementacja, ale rozważ dodanie testu negatywnego na `getResource`/`getResources` analogicznego do tych na `loadClass`.** Obecne testy (`PlatformApiClassLoaderTest`) pokrywają tylko ścieżkę klas; `isResourceInAllowedPackage` (linia 146-149) ma osobną logikę (prefiks bez wymogu `.` po `pluginsdk`, w przeciwieństwie do `isInAllowedPackage` które wymaga `.` albo exact match) — `isResourceInAllowedPackage("com/contactcenter/pluginsdkXfake/Foo.class")` zwróci `true` (bo `startsWith("com.contactcenter.pluginsdk")` bez wymogu granicy słowa), co jest niekonsystentne z `isInAllowedPackage` (która ma test `rejectsLookAlikePackagePrefix` właśnie na ten przypadek dla klas, ale nie ma odpowiednika dla zasobów). Nie widzę obecnie istniejącego pakietu `com.contactcenter.pluginsdkx`, więc nie jest to wykorzystywalne dzisiaj, ale to niekonsekwencja, którą łatwo przeoczyć przy przyszłej zmianie. Fix: ujednolić `isResourceInAllowedPackage` do tej samej logiki granic co `isInAllowedPackage` (`startsWith(PREFIX + "/")` zamiast gołego `startsWith(PREFIX)`), dodać test.

- **`PluginRuntimeManagerImpl.java:115` — `Class.forName(entryPointClassName, true, classLoader)` z `initialize=true` wykonuje static initializer pluginu przed jakąkolwiek dodatkową walidacją post-load.** To jest zgodne z modelem (plugin i tak jest kodem zaufanym po ASM-review), ale warto rozważyć, czy `onActivate` powinno być pierwszym miejscem, gdzie kod pluginu się wykonuje, czy czy static initializer (potencjalnie kosztowny/zawieszający się) powinien być również timeout-bounded — obecnie `invokeWithTimeout` otacza tylko `entryPoint.onActivate(context)` (linia 137-140), nie samo `newInstance()`/inicjalizację klasy (linia 115-117), które jest wykonywane synchronicznie na wątku wołającym `load()` bez timeoutu. Plugin ze złośliwym/zawieszającym się static initializerem zawiesi wątek wołający `load()` (prawdopodobnie request thread admina robiącego `enable`) bez ochrony timeout. Rozważ przeniesienie też `Class.forName`+`newInstance()` do tego samego `invokeWithTimeout`/executora.

- **Testy — bardzo dobra jakość, ale brakuje testu na TCCL leak opisany wyżej.** Skoro to Critical finding tego review, sugeruję dodać `PluginRuntimeManagerImplTest` (albo nowy test class) z pluginem testowym (przez `RuntimeTestPluginBuilder`), który w `onActivate` woła `Thread.currentThread().getContextClassLoader()` i próbuje `Class.forName` na klasę domenową — i zweryfikować, że **po naprawie** to rzuca `ClassNotFoundException`, tak jak już istniejące testy na explicit classloader.

### ✅ Positive Observations

- **`PlatformApiClassLoader` — projekt i dokumentacja delegacji do `super.loadClass()` są dokładne i poprawne.** Javadoc wyjaśnia explicit *dlaczego* delegacja (nie redefinicja przez `defineClass`) jest konieczna dla zachowania identyczności typu (`loader constraint` problem) — to pokazuje, że autor zrozumiał subtelny problem klasyczny dla wielo-classloaderowych systemów (OSGi-podobny), nie tylko skopiował wzorzec. Mechanizm filtra nazw w `loadClass` (linie 79-111) jest poprawny i kompletny dla swojego zakresu (explicit classloader): bootstrap JDK klasy delegowane do `null` loadera (nigdy do app classloadera), wszystko inne poza `pluginsdk.*` odrzucone przed dotknięciem `super.loadClass`.
- **`PluginClassLoader` — separate instance per `(tenant_id, plugin_key)`, weryfikowane testem na referencję obiektu, nie tylko na zachowanie.** `twoTenantsWithSameJarGetDifferentClassLoaderInstances` i `classesLoadedByDifferentInstancesAreDistinctClassObjects` to dokładnie właściwe testy dla "no shared static state" wymogu.
- **`PluginContextImpl` — `tenantId` faktycznie `final`, ustawiany wyłącznie przez konstruktor wołany przez `PluginRuntimeManagerImpl`, zero settera, zero metody SDK przyjmującej tenantId jako parametr.** Weryfikacja: `PluginContext` (SDK interfejs) nie ma `tenantId` w żadnej sygnaturze metody — to jest poprawnie "structurally impossible", nie tylko "przez dyscyplinę kodu", zgodnie z deklaracją ARCHITECTURE.md §11.3 punkt 3. Każda metoda dotykająca danych (`getCustomer`, `updateCustomerFields`, `getContact`, `appendContactNote`) poprawnie przechodzi przez `CustomerService`/`ContactService` → `TenantAwareRepository` z `tenantId` zamrożonym w konstruktorze, zweryfikowane do końca łańcucha (`CustomerRepository.findById` ma explicit double-check `customer.getTenantId().equals(tenantId)` nawet ponad RLS).
- **`updateCustomerFields` — namespacing `custom_fields.plugins.<pluginKey>` zaimplementowany poprawnie, zero flat-merge, zgodnie z regułą anti-overloaded-column (CLAUDE.md, RT-14).** Test `writesOnlyToNamespacedPluginBag` weryfikuje explicit, że istniejące pola platformy (`accountNumber`) są zachowane, a nowe dane trafiają wyłącznie do `plugins.<pluginKey>`.
- **`unload()` — kolejność operacji (usuń z `activeHandles` → `onDeactivate` best-effort → `unregister` z registry → `closeQuietly`) jest rozsądna i jest testowana przez `WeakReference` + `System.gc()` z sensownym, nie-flaky retry loop (20 rund, 100ms, brak nieskończonego retry).** Komentarz w teście o "Mockito gotcha" (silna referencja w historii inwokacji mocka) pokazuje rzadko spotykaną dbałość o to, żeby test GC faktycznie testował kod produkcyjny, nie artefakt frameworka testowego.
- **`entryPointClass` instancjonowany wyłącznie przez `getDeclaredConstructor().newInstance()` (bez argumentów) — brak żadnej alternatywnej ścieżki DI.** Potwierdzone i przetestowane (`instantiatesOnlyViaNoArgConstructor`).
- **`PluginHttpEgressClientImpt` — allow-list per host wyprowadzona z `grantedPermissions` (przecięcie z manifestem, ustalone w BE-100), nie z manifestu pluginu directly — broni przed privilege escalation przez sam plugin.** Domyślny `HttpClient.Redirect.NEVER` (brak explicit override) oznacza, że redirect-based allow-list bypass nie jest możliwy bez dodatkowego kodu.

### Summary

**Werdykt: NO-GO — wymaga poprawek blokujących przed merge do `EPIC-28`.**

Fundamenty izolacji (`PlatformApiClassLoader`, `PluginClassLoader`, `PluginContextImpl`, tenant-scoping na poziomie SDK facade) są zaimplementowane starannie, z dobrą dokumentacją i testami pokrywającymi explicit-classloader ścieżki ucieczki dokładnie tak, jak wymagały kryteria akceptacji. Jednak znalazłem jedną **Critical** dziurę realnie podkopującą deklarowaną gwarancję izolacji — TCCL (thread-context classloader) nie jest resetowany na granicy wywołania `onActivate`/`onDeactivate`, co pozwala kodowi pluginu (przypadkowo przez bibliotekę trzecią, lub świadomie) dotrzeć do pełnego classloadera aplikacji przez `Thread.currentThread().getContextClassLoader()` i obejść całą kontrolę `PlatformApiClassLoader`. ASM scanner (BE-098) też nie blokuje tę ścieżkę (`Thread#getContextClassLoader`/`setContextClassLoader`, `ServiceLoader#load` nie są na blackliście). To jest blokujące, bo dotyka dokładnie tego, co ten review miał zweryfikować jako priorytet #1.

Drugi blokujący problem (**High**, nie Critical) to potwierdzony wyciek plików tymczasowych JAR-a (`downloadJarToLocalCache`, nigdy czyszczone) — sam implementujący agent to zgłosił jako znane ograniczenie; potwierdzam że jest realny i że przy realnym użyciu (wielokrotne enable/disable, restarty) prowadzi do nieograniczonego wzrostu zużycia dysku węzła, czyli ryzyka DoS na cały JVM, nie tylko na plugin.

Trzeci problem (**Medium**) to naruszenie kontraktu SDK przez `PluginLoggerImpl` (logi pluginu wmieszane w logi aplikacji, wbrew explicit Javadoc SDK) — świadomie tymczasowe do BE-102, ale warto dodać minimalną sanityzację/limit długości teraz, żeby nie czekać do BE-102 z otwartym log-injection/log-flood wektorem.

**Lista blokujących (Critical/High) znalezisk:**
1. **Critical** — TCCL nie jest resetowany na granicy `onActivate`/`onDeactivate` (i przyszłych invocation w BE-102) → plugin może obejść `PlatformApiClassLoader` przez `Thread.currentThread().getContextClassLoader()`. Fix: snapshot/set/restore TCCL wokół każdego wywołania kodu pluginu + dodać `Thread#{get,set}ContextClassLoader`/`ServiceLoader#load` do ASM blacklist (`PluginBytecodeScanner`).
2. **High** — Brak czyszczenia plików tymczasowych JAR-a w `PluginRuntimeManagerImpl.downloadJarToLocalCache`/`unload()` → nieograniczony wzrost zużycia dysku przy realnym użyciu. Fix: usunąć plik w `unload()` (wymaga przechowania `Path` w handle) lub wdrożyć cache współdzielony per `pluginVersionId` z licznikiem referencji.

Po naprawie tych dwóch punktów (i rozważeniu Medium/Low jako follow-up, część jawnie zaplanowana na BE-102), ten kod jest gotowy do merge — architektura i dyscyplina tenant-scoping są solidne, testy są wysokiej jakości i adresują dokładnie kryteria akceptacji opisane w tickecie.

## Aktualizacja po fixie — 2026-06-20

Oba blokujące znaleziska (Critical + High) zostały naprawione. Werdykt zmienia się z **NO-GO** na gotowy do merge (BE-101 ✅ w `TASKS-BACKEND.md`).

1. **Critical (TCCL leak) — naprawione.** Nowa klasa `domain.plugin.runtime.PluginExecutionContext` z metodą statyczną `runWithPluginClassLoader(ClassLoader pluginClassLoader, Callable<T> action)` — snapshot aktualnego TCCL, ustawienie `pluginClassLoader`, wywołanie `action.call()` w `try`, przywrócenie poprzedniego TCCL w `finally` (wzorzec analogiczny do `TenantContext.snapshot()/restore()/clear()`). Zastosowana wokół OBU wywołań kodu pluginu w `PluginRuntimeManagerImpl` — `entryPoint.onActivate(context)` (w `load()`) i `handle.entryPoint().onDeactivate()` (w `unload()`), w obu przypadkach wewnątrz lambdy wykonywanej na `lifecycleExecutor` (wątek roboczy, gdzie problem faktycznie występował). Reużywalna sygnatura — gotowa dla BE-102 (`PluginInvocationExecutor`), który będzie wołać tę samą metodę dla wszystkich hooków rozszerzeń, nie tylko `onActivate`/`onDeactivate`. Defense in depth: `PluginBytecodeScanner.BLOCKED_METHOD_CALLS` rozszerzony o `java/lang/Thread#getContextClassLoader`/`setContextClassLoader`; `BLOCKED_OWNER_PREFIXES` o `java/util/ServiceLoader` — JAR-y próbujące jawnie użyć tych API są odrzucane przy walidacji (BE-098), niezależnie od fixu runtime. **Test regresyjny zweryfikowany empirycznie w obu kierunkach:** `PluginRuntimeManagerImplTest$LoadTests.onActivateRunsWithPluginClassLoaderAsThreadContextClassLoader` — plugin testowy (`RuntimeTestPluginBuilder.buildJarThatProbesTcclOnActivate`) w `onActivate` woła `Thread.currentThread().getContextClassLoader()` i `Class.forName("com.contactcenter.app.ContactCenterApplication", false, tccl)`. Przed zaaplikowaniem fixu (tymczasowo zakomentowany wrapper, uruchomiony izolowanie) test **faktycznie failował**: `expected: "NOT_FOUND" but was: "FOUND:com.contactcenter.app.ContactCenterApplication"` — potwierdzenie, że scenariusz ataku faktycznie się udawał. Po fixie: `ClassNotFoundException` przez `PlatformApiClassLoader` → `"NOT_FOUND"`, test przechodzi.
2. **High (wyciek plików tymczasowych) — naprawione.** `PluginInstanceHandle` (record) ma nowe pole `localJarPath` (`Path`), wypełniane w `load()` od razu po `downloadJarToLocalCache`. `unload()`: `deleteQuietly(handle.localJarPath())` wywoływane PO `closeQuietly(handle.classLoader())` (kolejność istotna — `URLClassLoader` musi zwolnić uchwyt pliku przed jego usunięciem, inaczej ryzyko niepowodzenia/pliku "zombie" na niektórych OS). Ścieżki błędu w `load()` (instancjonowanie `entryPointClass` rzuca, albo `onActivate` rzuca/timeout) również wywołują `deleteQuietly(localJarPath)` przed propagacją `PluginActivationException` — brak wycieku nawet przy nieudanej aktywacji. `deleteQuietly` jest best-effort (`Files.deleteIfExists` + log warn przy błędzie, nigdy nie przerywa `load()`/`unload()`). Test: `PluginRuntimeManagerImplTest$UnloadTests.unloadDeletesLocalJarCacheFile` — weryfikuje, że plik istnieje po `load()` i nie istnieje po `unload()`.
3. **Medium (PluginLoggerImpl log injection) — zaadresowane, mimo że oznaczone jako opcjonalne.** `sanitize()`: escape `\r`/`\n` (zapobiega log-forging) + truncate do 4000 znaków z sufiksem `"...[truncated]"` (zapobiega log-flood). Zastosowane we wszystkich trzech metodach (`info`/`warn`/`error`). Pełna naprawa kontraktu SDK (zapis do `plugin_invocation_log`, nie SLF4J współdzielony) pozostaje zakresem BE-102, zgodnie z oryginalną notatką w tym review.

**Weryfikacja:** `mvn verify -pl app` z `/home/pawelm/contact-center/backend` ✅ — **1218 testów, 0 failures, 0 errors, BUILD SUCCESS** (1216 istniejących + 2 nowe testy regresyjne).

**Plik(i) zmienione w ramach fixu:**
- `backend/app/src/main/java/com/contactcenter/domain/plugin/runtime/PluginExecutionContext.java` (nowy)
- `backend/app/src/main/java/com/contactcenter/domain/plugin/runtime/PluginRuntimeManagerImpl.java`
- `backend/app/src/main/java/com/contactcenter/domain/plugin/runtime/PluginInstanceHandle.java`
- `backend/app/src/main/java/com/contactcenter/domain/plugin/PluginBytecodeScanner.java`
- `backend/app/src/main/java/com/contactcenter/domain/plugin/runtime/PluginLoggerImpl.java`
- `backend/app/src/test/java/com/contactcenter/domain/plugin/runtime/PluginRuntimeManagerImplTest.java`
- `backend/app/src/test/java/com/contactcenter/domain/plugin/runtime/RuntimeTestPluginBuilder.java`
- `backend/app/src/test/java/com/contactcenter/domain/plugin/runtime/PluginRegistryImplTest.java` (sygnatura `PluginInstanceHandle` rozszerzona o `localJarPath`)

## Review: `externalId` na Customer (CRM external ID) — 2026-07-05

**Branch:** `customer-refactor`

**Zakres:** `V079__add_external_id_to_customer.sql`, `Customer.java`, `CustomerRepository.java`
(`findByExternalId`), `CustomerServiceImpl.java` (create/update, walidacja unikalności),
`CustomerImportServiceImpl.java` (kolumna CSV `external_id` + poprawka bugu param-count w
`batchInsertCustomers`/`batchUpdateCustomers`), DTO (`CreateCustomerRequest`, `UpdateCustomerRequest`,
`CustomerResponse`, `CustomerLookupResponse`), `CustomerServiceTest`, `CustomerControllerTest`,
`CustomerImportServiceTest`. Zweryfikowano kompilację (`mvn -pl app -am compile -o`) i uruchomiono
`CustomerServiceTest` (21/21), `CustomerControllerTest` (14/14), `CustomerImportServiceTest` (41/41) —
wszystkie zielone, `BUILD SUCCESS`.

### 🐛 Bugs / Critical Issues

- **`CustomerImportServiceImpl.java:346-376` (`batchInsertCustomers`) + `doImport` linie 268-271 — cichy data-loss przy imporcie CSV z kolidującym `external_id`.** SQL INSERT ma `ON CONFLICT DO NOTHING` **bez wskazanego celu konfliktu** — to znaczy, że przechwytuje ZAROWNO (nieosiągalną praktycznie) kolizję `customer_id` (gen_random_uuid), JAK I TERAZ realistyczną kolizję na nowym `uq_customer_tenant_external_id` (V079). Problem: licznik `imported++` (linia 270) jest inkrementowany w momencie dodania wiersza do batcha, **zanim** batch zostanie faktycznie wysłany do bazy (`flushBatch` dzieje się później, przy `BATCH_SIZE` albo na końcu pliku). Gdy dwa wiersze w tym samym imporcie (albo import powtórzony) mają ten sam `external_id`, drugi wiersz zostanie CICHO pominięty przez `ON CONFLICT DO NOTHING` — ale `imported` już go policzył. Efekt: raport joba pokaże `status=COMPLETED, imported=N`, mimo że faktycznie zapisano N-1 (lub mniej) rekordów, bez żadnego wpisu w `failed`/error CSV, bez WARN loga. Użytkownik nie ma żadnego sygnału, że rekord przepadł.
  - Fix: po `jdbcTemplate.batchUpdate(...)` sprawdzić zwrócony `int[]` (affected rows per statement) i dla indeksów z `0` przeklasyfikować wiersz na `failed`/`skipped` z konkretnym powodem ("Duplikat external_id") w raporcie błędów, zamiast optymistycznie liczyć przed flushem.

- **`CustomerImportServiceImpl.java:378-403` (`batchUpdateCustomers`, tryb OVERWRITE) — brak `ON CONFLICT` na UPDATE, kolizja `external_id` wysadza CAŁY pozostały import, nie tylko jeden wiersz.** W przeciwieństwie do INSERT, UPDATE nie ma żadnej klauzuli obsługi konfliktu — jeśli wiersz w trybie OVERWRITE próbuje ustawić `external_id`, które już należy do INNEGO klienta (dopasowanego przez inny phone/email), baza rzuci naruszenie unikalności wprost z `jdbcTemplate.batchUpdate()`. To wyleci jako nieprzechwycony wyjątek z `flushBatch()`, złapany dopiero przez ogólny `catch (Exception e)` w `doImport` (linia 295, konwertowany na `IOException`), a następnie przez `catch (Exception e)` w `processImportAsync` (linia 167) — cały job kończy się `FAILED_PARTIAL` z jednym komunikatem `"FATAL: ..."`, **tracąc wszystkie jeszcze nieprzetworzone wiersze pliku**, zamiast oznaczyć tylko kolidujący wiersz jako `failed`. To poważniejsza wersja tego samego braku obsługi — dedup w tym serwisie (`findExisting`, linia 257) sprawdza tylko phone/email, nigdy `external_id`.
  - Fix: przed dodaniem wiersza do batcha (insert LUB update) wykonać `customerRepository.findByExternalId(...)` analogicznie do `CustomerServiceImpl`, i jeśli zwróci innego klienta niż dopasowany po phone/email — sklasyfikować wiersz jako `failed` z jasnym powodem, zamiast pozwolić bazie rzucić wyjątek w środku batcha.

- **`CustomerServiceImpl.java:70-84` (`createCustomer`) i `:230-240` (`updateCustomer`) — pusty string `""` w `externalId` nie jest normalizowany do `NULL` przed zapisem.** Partial unique index z V079 wyklucza tylko `external_id IS NOT NULL` — pusty string **nie jest NULL**, więc dwóch klientów z `externalId=""` realnie koliduje na unikalności przy drugim zapisie (`DataIntegrityViolationException`, zmapowane ogólnie na 409 przez istniejący handler — patrz sekcja Architektura). W `updateCustomer` jest to szczególnie widoczne: `if (!request.externalId().isBlank()) { ...check... }` pomija sprawdzenie duplikatu dla pustego stringa, ale linia `customer.setExternalId(request.externalId());` i tak wykonuje się bezwarunkowo dla dowolnego non-null, **łącznie z pustym stringiem** — zapisując `""` zamiast `NULL`. W połączeniu z frontendem (`customer-edit.component.ts:161`, `raw.externalId?.trim() || undefined`), które nigdy nie wysyła pustego stringa (zamienia go na `undefined`, interpretowane przez backend jako "brak zmiany"), obecny UI nigdy nie trafia w ten bug — ale endpoint jest w pełni osiągalny wprost przez API/Swagger/przyszłych klientów, i **nie ma dziś żadnego sposobu, żeby wyczyścić już ustawiony `externalId`** (patrz też finding we `CR-FRONTEND.md`).
  - Fix: znormalizować `externalId` do `null` gdy `isBlank()` PRZED sprawdzeniem duplikatu i PRZED zapisem, w obu metodach: `String normalized = (request.externalId() == null || request.externalId().isBlank()) ? null : request.externalId();`. Rozważyć udokumentowanie explicit "wyczyść" semantyki dla tego pola (np. pusty string = wyczyść do NULL), analogicznie do `phone`/`email`, gdzie `[]` oznacza wyczyszczenie tablicy — obecnie Javadoc `UpdateCustomerRequest.externalId` mówi tylko "null = bez zmiany", nic o pustym stringu.

### ⚠️ Security Concerns

_Nie zidentyfikowano nowych zagrożeń bezpieczeństwa w tym PR._ Weryfikacja race condition (pkt 2 z briefu): proaktywny check `findByExternalId` + zapis nie jest atomowy (TOCTOU), ale `GlobalExceptionHandler.handleDataIntegrityViolationException` (kod istniejący, niezmieniony w tym PR) poprawnie łapie `DataIntegrityViolationException` i zwraca generyczny, bezpieczny `ProblemDetail` 409 — **brak wycieku surowego stack trace'u SQL do klienta**. To jest poprawnie zaimplementowane od wcześniej i działa również dla nowego indeksu `uq_customer_tenant_external_id`.

### 🏗️ Architecture / Pattern Violations

- **`CustomerServiceImpl.java:205-216` — Javadoc `updateCustomer` nie wspomina `externalId` w ogóle.** Dokumentuje tylko `firstName`/`lastName`/`phone`/`email` semantics (`null = bez zmiany`, `[] = wyczyść`), mimo że metoda teraz obsługuje dodatkowe pole z INNĄ semantyką błędów (rzuca `ConflictException`). Stała, niekompletna dokumentacja publicznego kontraktu API.
- **`CustomerImportServiceImpl.java:691-699` (`defaultColumnIndex()`) — dodanie `EXTERNAL_ID=4` zmienia interpretację 5. kolumny w trybie CSV bez nagłówka i bez jawnego mapowania.** Wcześniej ta pozycja była po prostu ignorowana (nikt jej nie odczytywał); teraz automatycznie staje się `external_id`. Frontend zawsze wysyła jawne mapowanie kolumn (`customer-import.component.ts`), więc UI nie jest tym dotknięte, ale każdy klient API omijający UI (skrypt integracyjny, stary format pliku) dostanie nowe, ciche zachowanie dla 5. kolumny. Nie blokujące, ale warto odnotować w changelogu/release notes dla integratorów.
- **`GlobalExceptionHandler` (niezmieniony w tym PR) nie ma dedykowanej obsługi `uq_customer_tenant_external_id`** analogicznej do już istniejącej dla `routing_rule_collision` (linia ~440) — kolizja `external_id` (rzadki wyścig przy równoczesnym create/update, patrz Bugs #1/#2 dla importu) trafia w generyczną gałąź "Naruszenie integralności danych", co działa bezpiecznie, ale nie mówi użytkownikowi, że to konkretnie duplikat `externalId`. Sugestia, nie blokada.

### 🔧 Improvements & Suggestions

- Brak testu dla scenariusza "wyczyszczenie `externalId` pustym stringiem" w `CustomerServiceTest` — istniejące testy (`updateCustomer_externalIdUnchangedForSameCustomer_succeeds`, `updateCustomer_externalIdTakenByAnotherCustomer_throwsConflictException`) nie pokrywają `request.externalId() == ""`, czyli dokładnie scenariusza opisanego w Bugs #3. Dodać test po naprawie normalizacji.
- Rozważyć dodanie testu na race condition (dwa równoległe `createCustomer` z tym samym `externalId` symulowane przez stub rzucający `DataIntegrityViolationException` z `customerRepository.save()`) — obecnie zachowanie na tej ścieżce jest pokryte tylko pośrednio przez ogólny handler, nie przez dedykowany test w `CustomerServiceTest`/`CustomerControllerTest`.
- `CustomerImportServiceTest` nowe testy (`ExternalIdColumn`) poprawnie weryfikują fix param-count (parametr `"CRM-42"` faktycznie trafia do przekazywanej tablicy), ale nadal mockują `JdbcTemplate` w całości — nie ma testu integracyjnego (Testcontainers) uderzającego w prawdziwy `uq_customer_tenant_external_id`, który złapałby Bugs #1/#2 powyżej. Warto rozważyć chociaż jeden test Testcontainers dla ścieżki importu z realną bazą, biorąc pod uwagę, że to już drugi raz (po historycznym błędzie param-count) gdy mockowanie `JdbcTemplate` maskuje realny problem w tym serwisie.

### ✅ Positive Observations

- **`V079__add_external_id_to_customer.sql` jest wzorcowa.** Nowy plik migracji (nie edycja zastosowanej), partial unique index `(tenant_id, external_id) WHERE external_id IS NOT NULL AND is_deleted = FALSE` poprawnie wyklucza NULL i zanonimizowane rekordy RODO, wzorowana na `uq_user_tenant_email` z V003, zero mutowalnych funkcji w predykacie. `customer` ma już `tenant_id`/`is_deleted`/`created_at`/`updated_at` od V006 — zgodność z checklistą DB potwierdzona.
- **Logika wykluczenia samego siebie w `updateCustomer`** (`.filter(existing -> !existing.getCustomerId().equals(customerId))`) jest poprawna — aktualizacja klienta bez zmiany `externalId`, albo zmiana tylko innych pól, nie wywołuje fałszywego `ConflictException`. To był konkretny punkt ryzyka wskazany w briefie i jest obsłużony poprawnie, potwierdzone testem `updateCustomer_externalIdUnchangedForSameCustomer_succeeds`.
- **Wszystkie miejsca budujące `CustomerLookupResponse`** (dwa w `CustomerServiceImpl`, linie ~321 i ~386) zostały zaktualizowane spójnie — brak przesunięcia pozycyjnego w konstruktorze rekordu, potwierdzone udaną kompilacją.
- **Poprawka historycznego bugu param-count w `batchInsertCustomers`/`batchUpdateCustomers`** (patrz `.claude/agent-memory/backend-dev-expert/feedback_jdbc_batchupdate_param_count_mismatch.md`) jest teraz zaimplementowana poprawnie i symetrycznie dla obu ścieżek — osobna tablica `insertParams`/`updateParams` budowana tuż przed `batchUpdate`, zamiast przekazywania surowego `row[]` ze znacznikiem. Potwierdzone przechodzącym dedykowanym testem i pełnym przebiegiem `CustomerImportServiceTest` (41/41).
- **Async TenantContext lifecycle w `processImportAsync`** poprawnie używa `restore(snapshot)` na wejściu i `clear()` w `finally` — zgodność z wymogiem architektonicznym dla granic wątków.
- `CustomerRepository.findByExternalId` wiernie odzwierciedla istniejący wzorzec `findByPhoneNumber`/`findByEmail` (`setTenantContextInDb`, natywny SQL, `@Transactional(readOnly = true)`) — dobra spójność stylu repozytorium.
- Kompilacja czysta (`mvn -pl app -am compile -o`), pełny zestaw testów dla dotkniętych klas zielony: `CustomerServiceTest` 21/21, `CustomerControllerTest` 14/14, `CustomerImportServiceTest` 41/41, `BUILD SUCCESS`.

### Summary

**Ocena: 3.5/5 ⭐** — solidne rozszerzenie domeny zgodne z konwencjami migracji/multi-tenancy i poprawiające realny, historyczny bug JDBC, ale z dwoma niezaadresowanymi lukami: cichą utratą danych / przerwaniem całego joba importu CSV przy kolizji `external_id` (Bugs #1/#2), oraz brakiem możliwości wyczyszczenia `externalId` po jego ustawieniu z powodu nieznormalizowanego pustego stringa (Bugs #3, wspólnie z frontendem). Rekomenduję naprawić normalizację pustego stringa i dodać obsługę kolizji `external_id` w ścieżce importu przed mergem do produkcji — pozostałe uwagi to usprawnienia, nie blokery.

## Review: CustomerImportServiceImpl — wielokolumnowy phone/email, nazwane custom_fields, import zgody RODO — 2026-07-05

**Branch:** `customer-refactor`

**Zakres:** `CustomerImportServiceImpl.java` (nowy record `ParsedMapping`, `getMultiColumn`, `buildGdprConsent`,
dwa warianty SQL UPDATE dla `gdpr_consent`), `CustomerImportServiceTest.java` (nowa klasa zagnieżdżona
`ColumnMappingAndConsent` + rozszerzenie `ParseColumnMapping`). Zweryfikowałem logikę statycznie
(diff + pełny plik), bez uruchamiania `mvn test` w tej sesji — wcześniejsza notatka w
`.claude/agent-memory/backend-dev-expert/project_be026_customer_import.md` potwierdza zielony przebieg.

### 🐛 Bugs / Critical Issues

- **`CustomerImportServiceImpl.java:553-571` (`buildInsertRow`) + `:1008-1021` (`buildGdprConsent`) — dla NOWEGO klienta `gdpr_consent` może nie zawierać w ogóle klucza `consent_given`, gdy zmapowana/wypełniona jest TYLKO kolumna `marketing_consent`.** Fallback na `{"consent_given": false}` (linie 556-558) uruchamia się wyłącznie, gdy `buildGdprConsent(...)` zwróci **całkowicie pustą** mapę (obie wartości `null`). Jeśli jednak `marketingConsent` jest niepuste, a `consentGiven` jest `null` (kolumna `consent_given` niezmapowana albo pusta komórka w tym konkretnym wierszu, przy zmapowanej `marketing_consent`), zapisany JSON to `{"marketing_consent": true, "consent_source": "CSV_IMPORT", "consent_date": "..."}` — **bez klucza `consent_given`**. To łamie inwariant ustanowiony przez `CustomerServiceImpl.defaultGdprConsent()` (linia 552-556 w `CustomerServiceImpl.java`), który dla KAŻDEGO klienta tworzonego przez pozostałe ścieżki API zawsze zapisuje `consent_given: false` jako baseline. Konsekwencja: klienci utworzeni przez CSV import mogą mieć niespójny kształt `gdpr_consent` względem klientów tworzonych ręcznie/API — dziś nieszkodliwe (brak zapytań SQL filtrujących po `gdpr_consent->>'consent_given'`), ale to pole jest z definicji compliance-krytyczne (RODO) i przyszły raport/filtr zgód (`WHERE gdpr_consent->>'consent_given' = 'true'`) zwróci `NULL` zamiast `false` dla tych rekordów — inny wynik niż dla reszty bazy. Reprodukowalne też przez UI (frontend pozwala zmapować `marketing_consent` bez mapowania `consent_given`).
  - Fix: budować bazową mapę z `consent_given=false` i nadpisywać sparsowanymi wartościami, zamiast warunkowego fallbacku tylko dla całkowicie pustej mapy:
    ```java
    Map<String, Object> gdprConsent = new LinkedHashMap<>();
    gdprConsent.put(COL_CONSENT_GIVEN, false); // baseline, spójny z CustomerServiceImpl.defaultGdprConsent()
    gdprConsent.putAll(buildGdprConsent(csvRow.consentGiven(), csvRow.marketingConsent()));
    String gdprConsentJson = toJson(gdprConsent);
    ```
    (Dla `buildUpdateRow` zachowanie zostaje bez zmian — merge JSONB `||` celowo NIE powinien wymuszać `consent_given`, żeby nie nadpisywać istniejącej wartości klienta gołym `false` przy re-imporcie samego `marketing_consent`.)
  - Brakujący test na tę lukę: żaden z nowych testów (`newCustomer_consentColumnsMapped_gdprConsentPopulated` i inne w `ColumnMappingAndConsent`) nie sprawdza przypadku "zmapowana/wypełniona TYLKO `marketing_consent`, `consent_given` puste/niezmapowane, tryb INSERT" — dodać test weryfikujący `gdpr.get("consent_given")` po naprawie.

### ⚠️ Security Concerns

_Nie zidentyfikowano nowych zagrożeń bezpieczeństwa w tej zmianie._ Kluczowy wymóg bezpieczeństwa danych z briefu — dwa warianty SQL UPDATE (`UPDATE_SQL_WITHOUT_CONSENT` / `UPDATE_SQL_WITH_CONSENT`, linie 434-463) wybierane raz na cały import przez `hasConsentMapping`, z merge JSONB `||` (nie nadpisaniem) — zweryfikowany jako poprawnie zaimplementowany i pokryty dedykowanymi testami regresyjnymi (`overwrite_noConsentMapping_updateSqlExcludesGdprConsent`, `overwrite_withConsentMapping_updateSqlMergesGdprConsent`). Zobacz szczegóły w sekcji Positive Observations.

### 🏗️ Architecture / Pattern Violations

- **Pre-istniejące, niezmienione w tym PR:** `batchInsertCustomers`/`batchUpdateCustomers` nadal operują przez surowy `JdbcTemplate` zamiast `TenantAwareRepository`/`assertSameTenant()` — `tenant_id` jest poprawnie bindowany jako parametr z `TenantContext` odtworzonego przez `snapshot()/restore()` (linia 141, 162), a UPDATE poprawnie filtruje `WHERE tenant_id = CAST(? AS uuid)`, więc nie jest to naruszenie bezpieczeństwa, ale odnotowuję jako świadomą architektoniczną różnicę od standardowego wzorca repozytorium — bez akcji, tylko do wiadomości przy przyszłych zmianach w tym serwisie.
- `ParsedMapping` jest package-private rekordem (nie `private`) celowo, żeby testy w tym samym pakiecie mogły go nazwać po typie — udokumentowane w komentarzu Javadoc i w pamięci `backend-dev-expert`; zgodne z konwencją projektu dla tego typu przypadków (por. `CsvRow`).

### 🔧 Improvements & Suggestions

- **`CustomerImportServiceImpl.java:1018`** — `consent_date` liczone przez `Instant.now().toString()` osobno DLA KAŻDEGO wiersza z niepustą zgodą. Poprawne funkcjonalnie, ale dla dużego pliku (tysiące wierszy) oznacza tysiące nieznacznie różniących się znaczników czasu w ramach jednego joba importu, zamiast jednego spójnego znacznika "czas rozpoczęcia importu". Rozważ obliczenie `Instant importStartedAt = Instant.now()` raz na początku `doImport` i przekazanie go do `buildGdprConsent`/`buildInsertRow`/`buildUpdateRow` — kosmetyczne, nieblokujące.
- Warto dodać test jawnie potwierdzający, że `hasConsentMapping` jest liczone raz na cały import również dla ścieżki `defaultColumnIndex()` (brak nagłówka + brak jawnego mapowania) — obecne testy pokrywają jawne mapowanie i auto-detekcję nagłówka, ale nie pozycyjny fallback. Ryzyko niskie (kod jawnie NIE mapuje kolumn zgody w `defaultColumnIndex()`), ale test zamknąłby lukę na przyszłość, gdyby ktoś kiedyś dodał domyślne pozycje dla `consent_given`/`marketing_consent`.

### ✅ Positive Observations

- **`ParsedMapping(single, multi, customFields, legacyCustomFieldsColumn)` jest czystą, dobrze udokumentowaną abstrakcją** nad trzema różnymi źródłami mapowania kolumn (jawne z frontendu, auto-detekcja nagłówka, pozycyjny fallback) — każde z trzech miejsc budujących mapowanie (`parseColumnMappingJson`, `buildColumnIndex`, `defaultColumnIndex`) konsekwentnie wypełnia te same cztery pola, co eliminuje ryzyko rozjazdu logiki między ścieżkami.
- **Wsteczna zgodność zweryfikowana i poprawna**: `"phone": 2` (pojedyncza liczba) poprawnie opakowywane w jednoelementową listę w `getMultiColumn`; `"custom_fields": 5` (pojedyncza liczba) poprawnie trafia do `legacyCustomFieldsColumn` i zachowuje dokładnie stare zachowanie (parsowanie zawartości komórki jako JSON, z fallbackiem na literalną wartość). Oba potwierdzone dedykowanymi testami (`backwardCompat_singleNumberPhoneMapping_stillWorks`, `backwardCompat_singleNumberCustomFieldsMapping_parsesJsonInCell`).
- **Łączenie wielu kolumn phone/email działa poprawnie na dwóch niezależnych poziomach jednocześnie**: `getMultiColumn` łączy wartości z WIELU kolumn CSV (np. `"phone":[2,5]`) ORAZ dla każdej z tych kolumn nadal dzieli komórkę po `;` (`splitMultiValue`) — oba mechanizmy poprawnie współistnieją, potwierdzone testami i ręczną analizą przepływu danych.
- **Kluczowy wymóg bezpieczeństwa RODO zaimplementowany poprawnie**: `hasConsentMapping` liczone raz na cały import (nie per wiersz) dla WSZYSTKICH trzech ścieżek mapowania (jawne mapowanie — przed pętlą `while`; auto-detekcja nagłówka i pozycyjny fallback — przy `rowNumber==1`, co efektywnie też liczy się raz). UPDATE bez zmapowanych kolumn zgody używa SQL-a, który w ogóle nie wspomina `gdpr_consent` w `SET` — nie samego pustego/no-op mergowania, tylko strukturalnie innego zapytania — najsilniejsza możliwa gwarancja przeciw przypadkowemu wyzerowaniu zgody. Merge (`||`, nie `=`) poprawnie zachowuje klucze niewymienione w nowej mapie.
- **Testy nowej funkcjonalności są dokładnie tam, gdzie powinny być** — dedykowany test regresyjny sprawdzający treść SQL-a (`doesNotContain("gdpr_consent")` / `contains("gdpr_consent = gdpr_consent || CAST(? AS jsonb)")`) zamiast tylko efektu końcowego, co czyni test odpornym na przyszłe refaktory i jednoznacznie dokumentującym intencję bezpieczeństwa.
- Refaktor `toJson(Map<String, ?> map)` (wildcard zamiast `Map<String,String>`) jest minimalny i trafny — jedna metoda obsługuje teraz zarówno `customFields` (`Map<String,String>`), jak i `gdpr_consent` (`Map<String,Object>` z boolean), bez duplikacji kodu serializacji.

### Summary

**Ocena: 4/5 ⭐** — solidna, dobrze przetestowana implementacja trzech niezależnych wymagań (multi-column, named custom_fields, RODO consent) z prawidłowo zaimplementowanym kluczowym wymogiem bezpieczeństwa danych (dwa warianty SQL UPDATE + merge JSONB) i pełną wsteczną zgodnością. Jedyna realna luka to brak normalizacji `consent_given` do jawnego `false` gdy tylko `marketing_consent` jest mapowane dla nowego klienta — drobna, łatwa do naprawienia niespójność danych, nie blokująca mergu, ale warta poprawki przed uznaniem funkcji za w pełni zamkniętą.


---

## Review: CustomerImportService / CustomerImportServiceImpl / CustomerImportController — import JSON (nowa ścieżka równoległa do CSV) — 2026-07-06

### 🐛 Bugs / Critical Issues

- **CONFIRMED (pre-istniejące, NIE wprowadzone w tym diffie, ale odziedziczone przez nową, współdzieloną `processRow` — dotyczy TERAZ zarówno CSV jak i JSON) — `CustomerImportServiceImpl.java:813-829` (`findExisting`) nie widzi wierszy oczekujących w bieżącym, jeszcze niewypchniętym batchu.** `findExisting` odpytuje wyłącznie `customerRepository` (realną bazę); nie ma żadnej struktury w pamięci (odpowiednika `seenExternalIds`, linia 313/532) śledzącej telefony/e-maile już dodane do `batch` w tym samym imporcie. Ponieważ `flushBatch` wykonuje się dopiero co `BATCH_SIZE=500` wierszy (linie 358, 547) LUB na końcu pliku, **dwa wiersze w tym samym pliku o tym samym telefonie/e-mailu, w tej samej (jeszcze niewypchniętej) partii, zostaną OBA potraktowane jako nowi klienci** — `existing.isEmpty()` będzie `true` dla obu, niezależnie od wybranego `DeduplicationMode` (SKIP i OVERWRITE zachowują się identycznie źle w tym scenariuszu, ponieważ decyzja SKIP/OVERWRITE zależy właśnie od wyniku `findExisting`). Efekt: dwa rekordy `customer` z identycznym numerem telefonu w tabeli, mimo że użytkownik jawnie poprosił o deduplikację. Zweryfikowałem też brak zabezpieczenia na poziomie DB: `V006__create_customer.sql` NIE ma unikalnego indeksu na `phone`/`email` (tylko GIN do wyszukiwania), więc `ON CONFLICT DO NOTHING` w `batchInsertCustomers` (linia ~701) nic tu nie chroni — jedyny realny unikalny indeks (`uq_customer_tenant_external_id`, `V079`) dotyczy `external_id`, który JEST poprawnie chroniony (`seenExternalIds` + `findByExternalId`).
  - Dla plików < 500 wierszy (prawdopodobnie zdecydowana większość realnych importów robionych przez supervisora) błąd dotyczy KAŻDEGO duplikatu telefonu/e-maila w pliku, nie tylko przypadków na granicy chunków — cały plik siedzi w jednej partii aż do końca pętli.
  - Brak testu pokrywającego ten scenariusz w obu ścieżkach: sprawdziłem `CustomerImportServiceTest.java` — istnieją testy `duplicateExternalIdWithinFile_secondRowFailed`/`duplicateExternalIdWithinFile_secondRowRejected` (external_id), ale ŻADEN test nie sprawdza duplikatu telefonu/e-maila WEWNĄTRZ jednego pliku.
  - Fix: dodać lokalny `Map<String, Integer>`/`Set<String>` (analogiczny do `seenExternalIds`) śledzący znormalizowane telefony/e-maile już dodane do `batch` w bieżącym imporcie, i sprawdzać go w `processRow` PRZED/RAZEM z `findExisting` (albo scalić obie ścieżki w jedno wyszukiwanie: baza + pending batch). To wymaga zmiany współdzielonej logiki, więc naprawia od razu oba formaty (CSV i JSON).
  - Priorytet: wysoki — to błąd integralności danych (tworzy duplikaty klientów), a nie tylko luka w pokryciu testami; wart naprawy niezależnie od tego, że nie został wprowadzony przez ten PR.

- **Drobna niespójność CSV vs JSON — `CustomerImportServiceImpl.java:611-617` (`jsonStringOrNull`) nie przycina białych znaków, w przeciwieństwie do ścieżki CSV.** `getColumnByIndex` (linia ~1257, używana przez `parseRow` dla CSV) robi `line[index].trim()` dla `first_name`/`last_name`. `jsonStringOrNull` (używane przez `parseJsonRow` dla `firstName`/`lastName`) robi tylko `String.valueOf(value)` + sprawdzenie `isBlank()`, bez `.trim()`. Rekord `{"firstName": " Jan "}` zaimportowany z JSON zachowa spacje w `first_name`, podczas gdy analogiczny wiersz CSV `" Jan ",...` zostanie przycięty do `"Jan"`. `externalId` jest przycinany później w `processRow` (linia 424), więc tego pola problem nie dotyczy — dotyczy tylko `firstName`/`lastName`.
  - Fix: `return s.isBlank() ? null : s.trim();` w `jsonStringOrNull`.

### ⚠️ Security Concerns

_Brak nowych zagrożeń bezpieczeństwa._ Zweryfikowałem punkt 4 z briefu: `source` w `batchInsertCustomers` (linia 693-726) jest poprawnie bindowany jako parametr JDBC (`?` na 9. pozycji placeholderów, `insertParams` zawiera `source` jako element listy przekazywanej do `jdbcTemplate.batchUpdate`), NIE konkatenowany do stringa SQL — potwierdzone czytaniem SQL-a (`CAST(? AS jsonb), ?, false, ?)`) i mapowania `insertParams`. Wartość pochodzi wyłącznie z prywatnych stałych (`CONSENT_SOURCE_CSV_IMPORT`/`CONSENT_SOURCE_JSON_IMPORT`), nigdy z danych użytkownika — brak ryzyka SQL injection, nawet teoretycznego.

Zweryfikowałem też, że nowy endpoint `POST /api/customers/import/json` NIE wymaga rejestracji w `SecurityConfig`/`TenantFilter.PUBLIC_PATH_PREFIXES` (nie jest publiczny) — `SecurityConfig.java:171` (`anyRequest().authenticated()`) + `@PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")` na metodzie kontrolera poprawnie go chronią, identycznie jak istniejący endpoint CSV. `/api/customers/import` nie występuje w `TenantFilter.PUBLIC_PATH_PREFIXES` — potwierdzone grep-em.

### 🏗️ Architecture / Pattern Violations

- **`CustomerImportServiceImpl.java:510-524` (`doJsonImport`) wczytuje CAŁY plik JSON do pamięci** (`objectMapper.readTree` → `JsonNode` → `objectMapper.convertValue(..., List<Map<String,Object>>)`), w przeciwieństwie do CSV, które strumieniuje wiersz po wierszu przez `CSVReader` (stała pamięć niezależnie od rozmiaru pliku). Dla pliku bliskiego limitowi 50 MB, reprezentacja jako drzewo `JsonNode` + `List<Map<String,Object>>` (narzut per-obiekt Java: `HashMap`/`LinkedHashMap` na wpis, boxing itd.) może zająć wielokrotnie więcej pamięci niż surowy rozmiar pliku. Przy kilku równoległych jobach importu (`@Async("applicationTaskExecutor")`, brak globalnego limitu współbieżności widocznego w tym pliku) to realne ryzyko presji na stertę/GC w środowisku multi-tenant. Nie blokujące (limit 50 MB prawdopodobnie został dobrany świadomie), ale warte odnotowania jako świadomy kompromis architektoniczny — rozważyć w przyszłości `JsonParser` w trybie strumieniowym (Jackson streaming API) jeśli limit kiedyś wzrośnie.
- Reszta refaktoru (`processRow`, `ImportCounters`, parametryzacja `source`) poprawnie zachowuje wzorzec ustalony w poprzednim review tego pliku (merge JSONB zamiast nadpisania, `hasConsentMapping` liczone raz na cały import) — architektura współdzielonej logiki między CSV i JSON jest czysta i nie duplikuje kodu.

### 🔧 Improvements & Suggestions

- **`doJsonImport` zna `totalRows` (`rows.size()`) natychmiast po sparsowaniu pliku (linia 534), ale nigdy nie zapisuje go do stanu joba w Redis przed `finalizeImportState`.** W przeciwieństwie do CSV (gdzie `total` faktycznie nie jest znane przed przeczytaniem całego strumienia), tutaj cały plik jest już w pamięci — `state.put("total", totalRows)` mogłoby zostać ustawione zaraz po przejściu do `PROCESSING`, dając klientowi pollującemu status realny pasek postępu (`processed`/`total`) już w trakcie przetwarzania, zamiast `total=0` aż do samego końca. Niski koszt, wyraźna poprawa UX pollingu dla dużych plików JSON.
- **`parseJsonRow` (linia 590-597, `customFields`) używa `String.valueOf(entry.getValue())` dla wartości zagnieżdżonych (Map/List) w `customFields`.** Dla wejścia typu `{"customFields": {"tags": ["a","b"]}}` wynikiem będzie Java `toString()` (`"[a, b]"`), a dla zagnieżdżonej mapy coś w stylu `"{y=1}"` — nie jest to poprawny JSON i wygląda myląco w UI. Udokumentowany format (`customFields (obiekt {nazwa: wartość})`) zakłada płaskie wartości, więc to nie jest naruszenie kontraktu, ale warto albo jawnie zserializować przez `objectMapper.writeValueAsString(value)` gdy wartość nie jest prymitywem, albo odrzucić/zalogować taki wiersz z czytelnym komunikatem zamiast cicho zapisywać zniekształcony string.
- **Niespójny format komunikatu błędu przy niepoprawnej składni JSON.** `doJsonImport` (linia 510-524) ładnie opakowuje przypadek "root nie jest tablicą" w czytelny komunikat (`"Plik JSON musi zawierać tablicę obiektów klientów."`), ale prawdziwie niepoprawna składnia JSON (np. urwany plik, brak domykającego nawiasu) rzuci `JsonParseException` (który `extends IOException`) już w `objectMapper.readTree(...)`, a to jest łapane przez `catch (IOException e) { throw e; }` — czyli propaguje się z surowym komunikatem Jacksona zamiast przez ujednolicony prefiks `"Błąd parsowania JSON: "` (który dostają inne wyjątki przez drugi `catch (Exception e)`). Funkcjonalnie nieszkodliwe (job i tak kończy się `FAILED_PARTIAL` + wpis FATAL — brak wycieku wyjątku, brak złego stanu w Redis), ale niespójne UX komunikatu błędu. Brakuje też dedykowanego testu dla TEGO konkretnego przypadku (niepoprawna składnia, a nie tylko "poprawny JSON, zły typ roota") — istniejące testy (`rootIsObject_notArray_failsWithFatalError`, `rootIsNumber_notArray_failsWithFatalError`) pokrywają tylko drugi przypadek.
- Rozważyć jawną walidację pustej tablicy `[]` (0 wierszy) w `doJsonImport` — obecnie kończy się cicho jako `COMPLETED` z `processed=0, total=0, imported=0`, bez żadnego ostrzeżenia że plik był pusty. Nie jest to błąd (być może to celowe, symetryczne z zachowaniem CSV dla pliku z samym nagłówkiem), ale UX-owo warto rozważyć wyraźny sygnał "plik nie zawierał żadnych rekordów" zamiast statusu nieodróżnialnego od udanego importu 0 nowych klientów. Zobacz też odpowiadającą uwagę we `CR-FRONTEND.md` (ten sam przypadek brzegowy, tam bardziej dotkliwy bo blokuje wyświetlenie ostrzeżenia przed wysłaniem).

### ✅ Positive Observations

- **Parametryzacja `source` jako bindowany parametr JDBC zamiast literału `'CSV_IMPORT'` w SQL** to czysty refaktor bez regresji — poprawnie zweryfikowany zarówno pod kątem SQL injection (patrz sekcja Security), jak i pod kątem kolejności placeholderów (`insertParams` w `batchInsertCustomers` dokładnie odpowiada kolejności `?` w `VALUES (...)`).
- **`processRow` jest solidną, dobrze udokumentowaną ekstrakcją wspólnej logiki** (walidacja telefonu → duplikat external_id w pliku → kolizja external_id w bazie → wyszukanie istniejącego klienta → decyzja SKIP/OVERWRITE) — kolejność sprawdzeń jest identyczna z kodem sprzed refaktoru (zweryfikowane linia po linii przeciwko `git show HEAD:...`), więc **brak regresji funkcjonalnej w ścieżce CSV** jest potwierdzony nie tylko przez przechodzące testy, ale przez bezpośrednie porównanie starej i nowej logiki.
- **`hasConsentMapping` dla importu JSON poprawnie liczone dla CAŁEGO pliku** (`rows.stream().anyMatch(this::rowHasConsentMapping)`, linia 529) PRZED rozpoczęciem pętli przetwarzającej — dokładnie zgodne z wymogiem ochrony RODO z briefu ("czy KTÓRYKOLWIEK wiersz w całym pliku"). Test regresyjny `overwrite_noConsentInAnyRow_updateSqlExcludesGdprConsent` faktycznie asercjuje treść SQL-a (`doesNotContain("gdpr_consent")`), a nie tylko efekt uboczny — to właściwy sposób testowania tego wymogu i sam sprawdziłem, że asercja rzeczywiście weryfikuje to, co ma weryfikować (nie jest to fałszywie pozytywny test).
- **Test `importCustomersJson_bindsDeduplicationParam_toServiceCall` w `CustomerImportControllerTest.java` wprost odtwarza znany wcześniej bug klasy "brak `name=` w `@RequestParam` cicho ignorowane przez Spring"** — dobra praktyka: test bezpośrednio asercjuje przekazaną wartość `OVERWRITE` (nie domyślną `SKIP`), więc realnie chroni przed powtórką tego konkretnego, wcześniej realnego błędu w tej samej klasie.
- Propagacja `TenantContext` (snapshot/restore/clear w finally) w `processJsonImportAsync` jest 1:1 skopiowana z już zweryfikowanego wzorca `processImportAsync` — poprawna, bez wycieku kontekstu między wątkami.

### Summary

**Ocena: 3.5/5 ⭐** — dobrze przetestowany, architektonicznie czysty refaktor współdzielonej logiki CSV/JSON, poprawna parametryzacja SQL i poprawna, dobrze przetestowana ochrona RODO przy OVERWRITE. Jedno realne obniżenie oceny: potwierdzony błąd deduplikacji telefonu/e-maila w obrębie jednego (jeszcze niewypchniętego) chunku — nie wprowadzony przez ten PR, ale teraz dotyczy dwóch ścieżek importu zamiast jednej, wysoki priorytet naprawy niezależnie od pochodzenia.

## Review: CampaignImportServiceImpl.java, CampaignImportService.java, CampaignImportController.java, CampaignImportServiceTest.java, CampaignImportControllerTest.java — 2026-07-12

Kontekst: rozszerzenie importu kontaktów kampanii wychodzącej o alternatywną ścieżkę JSON (obok istniejącego CSV), wzorowane na analogicznym rozszerzeniu `CustomerImportServiceImpl` (BE-026). Pełny plan: `staged-yawning-raven.md`. Backend i frontend implementowane równolegle, bez wzajemnej wiedzy — w tym review zweryfikowano też zgodność kontraktu między nimi.

### 🐛 Bugs / Critical Issues

- **`CampaignImportServiceImpl.java:479-484` (`doJsonImport`) — element `null` w tablicy JSON powoduje `NullPointerException`, który zabija CAŁY job importu zamiast odrzucić tylko ten jeden wiersz.** Pętla `for (int i = 0; i < rows.size(); i++) { CsvRow csvRow = parseJsonRow(rows.get(i)); ... }` znajduje się POZA blokiem `try/catch` który opakowuje `objectMapper.readTree`/`convertValue` (linie 456-470) — ten blok łapie tylko błędy parsowania/konwersji całego drzewa. Zweryfikowałem eksperymentalnie (Jackson 2.17.1, ten sam co w projekcie): `objectMapper.convertValue(root, new TypeReference<List<Map<String,Object>>>(){})` dla wejścia `[{"phone":"+1"}, null, {"phone":"+2"}]` **nie rzuca wyjątku** — po prostu zwraca listę zawierającą Javowy `null` na pozycji 1 (Jackson akceptuje `null` jako poprawną wartość dla typu referencyjnego). Dopiero `parseJsonRow(rows.get(i))` (linia 506, `obj.get("phone")`) rzuca NPE, który propaguje się przez pętlę w `doJsonImport` (brak lokalnego try/catch) aż do `processJsonImportAsync` (linia ~281, catch-all), kończąc **cały import** jako `FAILED_PARTIAL` z próbką `"FATAL: Cannot invoke \"java.util.Map.get(Object)\" because ... is null"` — nawet jeśli plik zawierał tysiące poprawnych rekordów. To niespójne z zaimplementowanym wzorcem odrzucania POJEDYNCZYCH wierszy (np. zły telefon → 1 odrzucony rekord, reszta importowana normalnie). Brak testu pokrywającego ten przypadek (istniejące testy pokrywają tylko "root nie jest tablicą" i "niepoprawna składnia JSON", nie "poprawna tablica z elementem `null`/nie-obiektem"). Ryzyko w praktyce ograniczone przez frontend (`campaign-import.component.ts:278`, `item !== null` w `isArrayOfObjects` blokuje wysyłkę takiego pliku przez UI — patrz CR-FRONTEND.md), ale endpoint jest wywoływalny bezpośrednio (Swagger, Postman, przyszły inny klient), więc backend powinien się bronić niezależnie od frontendu.
  - **Ten sam wzorzec (identyczna podatność) istnieje już w `CustomerImportServiceImpl.doJsonImport`/`parseJsonRow`** (referencyjny wzorzec, z którego skopiowano tę implementację) — nie jest to regresja wprowadzona przez ten PR, ale warto naprawić w obu miejscach przy okazji, skoro temat jest świeży.
  - Fix: albo jawny null-check `if (rows.get(i) == null) { jobStatus.addRejectedSample(...); rejectedRows.incrementAndGet(); continue; }` przed wywołaniem `parseJsonRow`, albo opakować całe ciało pętli (`parseJsonRow` + `processRow`) w try/catch traktujący dowolny wyjątek jako odrzucenie tego jednego wiersza (spójnie z resztą logiki walidacji per-wiersz).

### ⚠️ Security Concerns

_Brak nowych zagrożeń._ Zweryfikowałem:
- Endpoint `POST /{id}/contacts/import/json` ma `@PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")` — identycznie jak CSV.
- `/api/campaigns/**` nie występuje w `SecurityConfig` permit-list ani w `TenantFilter.PUBLIC_PATH_PREFIXES` (potwierdzone grep-em) — słusznie, to nie jest nowy publiczny endpoint, więc nie wymagał rejestracji w żadnym z tych dwóch miejsc.
- `requireCampaignInTenant` (wydzielone z `initiateImport`, reużyte przez `initiateJsonImport`) poprawnie chroni przed cross-tenant access — `campaignRepository.findById(campaignId, tenantId)` filtruje po tenancie PRZED utworzeniem jakiegokolwiek stanu joba w Redis.
- `@RequestParam`/`@RequestPart` w nowym endpoincie JSON są w pełni jawne co do intencji (`@RequestPart("file")` z jawną nazwą; `skipDuplicates` bez jawnej nazwy, ale identycznie jak w już działającym endpoincie CSV) — zweryfikowałem że `-parameters` jest włączone w `pom.xml:163` (kompilator zachowuje nazwy parametrów), więc wiązanie po nazwie zmiennej faktycznie działa; dodatkowo nowy `CampaignImportControllerTest` wykonuje REALNE żądanie HTTP przez `MockMvc.multipart(...)` (nie wywołanie metody bezpośrednio), więc faktycznie wykryłby regresję nazw parametrów, tak jak opisano w briefie jako znany wcześniej klasę bugów w tym projekcie.

### 🏗️ Architecture / Pattern Violations

_Brak naruszeń._ Refaktor jest czystą, mechaniczną ekstrakcją — zweryfikowałem linia po linii przez `git diff`, że `doImport` (CSV) po refaktorze wykonuje dokładnie te same kroki w tej samej kolejności co przed zmianą (kolejność flush/save-progress, warunek `batch.size() >= BATCH_SIZE`, brak aktualizacji postępu przy końcowym flushu). Potwierdzone też przez pełny przebieg testów (patrz Positive Observations). Propagacja `TenantContext` (`snapshot()` na wątku HTTP → `restore()` + `clear()` w `finally` na wątku `@Async`) w `processJsonImportAsync` jest 1:1 skopiowana z już zweryfikowanego wzorca `processImportAsync`.

Podobnie jak w poprzednim review analogicznej ścieżki dla `CustomerImportServiceImpl`: `doJsonImport` (linia 447-472) wczytuje CAŁY plik JSON do pamięci (`readTree` → `JsonNode` → `List<Map<String,Object>>`), w przeciwieństwie do CSV które strumieniuje. Dla pliku bliskiego limitowi 50 MB to realnie wielokrotnie więcej pamięci niż surowy rozmiar pliku. Nie blokujące (świadomy kompromis, spójny z już zaakceptowanym odpowiednikiem dla klientów), ale wart odnotowania przy kolejnych rundach tego wzorca.

### 🔧 Improvements & Suggestions

- **`parseJsonRow` (linia 506-517, `customFields`) używa `String.valueOf(entry.getValue())` dla zagnieżdżonych wartości (Map/List) w `customFields`** — dla wejścia typu `{"customFields": {"tags": ["vip","churned"]}}` wynikiem będzie Javowy `List.toString()` (`"[vip, churned]"`), nie poprawny JSON, a po serializacji całego `customFields` przez `toJson()` (linia ~879) trafi to do bazy jako string wewnątrz stringa, tracąc strukturę bezpowrotnie i bez żadnego ostrzeżenia. **Identyczna usterka została już zidentyfikowana w `CustomerImportServiceImpl.parseJsonRow`** we wcześniejszym review — potwierdza to wzorzec kopiowania tej samej słabości między dwiema ścieżkami importu JSON w tym repo. Udokumentowany format zakłada płaskie wartości (przykłady w opisie endpointu pokazują tylko `"segment": "gold"`), więc to nie jest naruszenie kontraktu, ale warto zserializować przez `objectMapper.writeValueAsString(value)` gdy wartość nie jest prymitywem, zamiast cicho zapisywać zniekształcony string.
- Brak testu dla przypadku "poprawna tablica JSON, ale z elementem `null` lub elementem nie-obiektem pomieszanym z poprawnymi obiektami" — dodanie takiego testu ujawniłoby błąd z sekcji Bugs.
- Drobna, pozytywna zmiana przy okazji refaktoru: stary log chunków CSV zawierał literalny placeholder `"?"` zamiast realnej wartości (`"przetworzono={}/{}, zaimportowano={}", totalRows.get(), "?", importedRows.get()`) — nowy `flushBatch` (linia ~604) poprawił to na `"przetworzono={}, zaimportowano={}, odrzucono={}"` z realnymi wartościami, w tym dodał licznik odrzuconych, którego wcześniej nie było w logu chunka. Nie proszono o to w planie, ale to trafna poprawka obserwowalności.

### ✅ Positive Observations

- **Zero regresji w zachowaniu CSV po refaktorze** — potwierdzone nie tylko czytaniem diffu (mechaniczna ekstrakcja `processRow`/`flushBatch`/`finalizeJobStatus`/`requireCampaignInTenant`/`buildQueuedStatus`, bez zmiany logiki), ale i uruchomieniem całego `mvn test -pl app`: **1499 testów, 0 failures, 2 errors** — dokładnie te same 2 błędy co przed zmianą (`ContactCenterApplicationIT`, niezwiązane z importem, pre-existing).
- **`CampaignImportControllerTest` (nowy plik) poprawnie wzorowany na `CustomerImportControllerTest`** — wykonuje realne żądania HTTP przez `MockMvc.multipart(...)` zamiast wywoływać metody kontrolera bezpośrednio, co faktycznie weryfikuje wiązanie `@RequestParam`/`@RequestPart`/`@PathVariable` przez Spring (dokładnie ten mechanizm, w którym wcześniej w tym projekcie wykryto realny bug). 4 nowe/zweryfikowane testy przechodzą (2 CSV regresyjne + 2 JSON).
- **Kontrakt między CSV a JSON dla `skipDuplicates`/`ON CONFLICT` zweryfikowany jako identyczny** — `flushBatch` przekazuje `skipDuplicates` do `campaignContactRepository.batchInsert(tenantId, campaignId, batch, skipDuplicates)` tą samą ścieżką dla obu formatów, bez rozgałęzień; domyślna wartość `true` spójna między interfejsem serwisu i kontrolerem dla obu endpointów.
- **Dobre pokrycie testowe nowych przypadków brzegowych JSON** (root nie jest tablicą, niepoprawna składnia, pusta tablica, odrzucone wiersze z powodu złego telefonu, kampania nieznaleziona, zła ekstensja) — asercje sprawdzają rzeczywistą treść payloadu w Redis (`totalRows`/`importedRows`/`rejectedRows`/status), nie tylko brak wyjątku.
- **Poprawne obejście znanej pułapki Mockito** w teście `validJsonImport_insertsRows_completesJob` — `batch` przekazywany do `campaignContactRepository.batchInsert` jest czyszczony (`batch.clear()`) zaraz po wywołaniu przez `flushBatch`, więc zwykły `ArgumentCaptor.getValue()` po teście zwróciłby pustą listę; test poprawnie kopiuje argument obronnie przez `thenAnswer` w momencie wywołania — udokumentowane też w pamięci agenta backendowego, dobra praktyka do powtórzenia.
- Kontrakt frontend↔backend zweryfikowany jako w pełni zgodny: `campaign.service.ts.importContactsJson()` wysyła `FormData` z polami `file`/`skipDuplicates` na `POST /{campaignId}/contacts/import/json`, dokładnie odpowiadając `@RequestPart("file")` + `@RequestParam(defaultValue="true") boolean skipDuplicates` w kontrolerze — brak żadnej rozbieżności nazw mimo niezależnej implementacji przez dwóch różnych agentów.

### Summary

**Ocena: 4/5 ⭐** — czysty, w pełni przetestowany refaktor bez regresji w ścieżce CSV (potwierdzone pełnym przebiegiem testów), poprawna izolacja multi-tenant i propagacja `TenantContext`, prawidłowa rejestracja bezpieczeństwa endpointu. Jedna realna, wąska ale prawdziwa usterka: element `null` w tablicy JSON zabija cały job zamiast jednego wiersza (w praktyce częściowo osłonięte przez frontend, ale backend powinien być odporny niezależnie) — do naprawienia przed uznaniem funkcji za w pełni domkniętą, najlepiej razem z analogicznym miejscem w `CustomerImportServiceImpl`.

---

## Review: Refaktor ról SUPER_ADMIN/ADMIN/SUPERVISOR/AGENT (backend, working tree na `rule-refactor`) — 2026-07-12

Kontekst: zmiana o wysokim priorytecie bezpieczeństwa — nowa rola globalna `SUPER_ADMIN` (`tenant_id IS NULL`), `ADMIN` staje się w pełni tenant-scoped i przejmuje dawny zakres `SUPERVISOR`, `SUPERVISOR` traci 5 obszarów technicznych (email/SMTP, social OAuth, Twilio, AI config, pluginy) ale zachowuje kolejki/IVR/numery/routing jako biznesowe. Pełny plan: `linked-questing-sedgewick.md`. Przegląd objął wszystkie niezacommitowane pliki backendu związane z refaktorem: `V080__add_super_admin_role.sql`, `V081__refresh_token_nullable_tenant_id.sql`, `AppUser`/`AppUserRepository`, `UserService`/`UserServiceImpl`, `AuthServiceImpl`, `AdminUserServiceImpl`, `JwtService`/`JwtParser`/`JwtAuthFilter`/`TenantFilter`/`UserDetailsServiceImpl`/`SecurityConfig`, `SuperAdminBootstrapRunner` (nowy), `CrossTenantAspect`, `TenantServiceImpl`, wszystkie zmienione kontrolery (`AdminMetricsController`, `EtlStatusController`, `AuditLogController`, `TenantController`, `AdminUserController`, `EmailController`, `SocialOAuthController`, `TenantAiConfigController`, `TenantTwilioConfigController`, `PluginAdminController`, `PluginInvocationLogController`, `PluginRevokeController`, `PluginUploadController`, `PublicController`, `AuthController`) oraz odpowiadające testy.

Zweryfikowałem ze szczególną uwagą dwie ręczne poprawki opisane w briefie jako dopisane przez główny agent PO tym, jak `backend-dev-expert` zgłosił je jako niedokończony dług techniczny: `TenantServiceImpl.assertSameTenantUnlessSuperAdmin()` (użyta w `getTenantConfig`/`updateTwilioConfig`) oraz `AuthServiceImpl.forcePasswordReset()` (asercja `!"SUPER_ADMIN".equals(callerRole) && !Objects.equals(target.getTenantId(), callerTenantId)` + `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ADMIN','SUPERVISOR')")` w `AuthController`). Obie poprawki są poprawne, null-safe (`Objects.equals` chroni przed NPE gdy target lub caller ma `tenantId == null`) i mają dedykowane testy regresyjne jawnie nazwane jako pokrywające IDOR (`TenantServiceTest.UpdateTwilioConfig.adminCannotUpdateOtherTenantConfig` z komentarzem „regresja IDOR”, `AuthServiceTest.ForcePasswordResetTests.forcePasswordReset_adminCannotResetOtherTenantUser_crossTenantBlocked`). Nie znalazłem żadnego INNEGO miejsca w zmienionych plikach z tym samym wzorcem błędu (rola formalnie tenant-scoped bez faktycznej asercji tenant_id) — przeszukałem systematycznie wszystkie kontrolery dopuszczające ADMIN/SUPERVISOR do endpointów przyjmujących `tenantId`/`userId` jako parametr ścieżki i w każdym innym przypadku albo serwis pobiera zasób przez `TenantAwareRepository`/`tenantId` z `TenantContext` (bezpieczne z definicji), albo endpoint nie przyjmuje cudzego identyfikatora w URL.

### 🐛 Bugs / Critical Issues

- **`CrossTenantAspect.java:118-169` (`verifyTenantContext`) — fałszywy alarm ERROR przy KAŻDYM żądaniu HTTP użytkownika SUPER_ADMIN dotykającym dowolnego serwisu domenowego.** `TenantFilter` celowo NIE wywołuje `TenantContext.setTenantId(...)` dla SUPER_ADMIN (brak tenanta) — ale WYWOŁUJE `TenantContext.setUserRole("SUPER_ADMIN")` i `setUserId(...)`. `TenantContext.isSet()` sprawdza wyłącznie `TENANT_ID.get() != null` (nie userId/role), więc dla każdego uwierzytelnionego żądania SUPER_ADMIN zwraca `false`. `verifyTenantContext()` poprawnie rozpoznaje bootstrap logowania (`findAuthenticatableGlobalUser` dodane do `isAuthenticationBootstrapMethod`), ale NIE rozpoznaje analogicznego, dużo częstszego przypadku: SUPER_ADMIN już zalogowany, wykonujący zwykłe żądanie do `GET /api/tenants`, `POST /api/admin/users`, `GET /api/admin/metrics/...` itd. URI tych endpointów nie pasuje do `isExpectedPublicPath()` (słusznie — to NIE są publiczne endpointy), więc kod wchodzi w gałąź `else` i loguje `log.error("[CrossTenant][Config] TenantContext NIE JEST ustawiony ... błąd konfiguracji")` — dla KAŻDEGO wywołania metody `@Service` w pakiecie `domain` (np. `TenantServiceImpl.listTenants()`, `AdminUserServiceImpl.createUser()`, `AdminMetricsService`, `EtlSyncService`, `AuditLogService`) w ramach obsługi żądania SUPER_ADMIN. Komentarz w pliku wprost mówi „poziom WARN jest monitorowany przez SIEM” — ERROR tym bardziej. Skutek: normalna, oczekiwana aktywność jedynego globalnego administratora platformy zasypuje logi produkcyjne fałszywymi alarmami „błąd konfiguracji TenantFilter”, co w praktyce dewaluuje sygnał tego aspektu bezpieczeństwa (alert fatigue — prawdziwa błędna konfiguracja utonie w szumie). Nie jest to luka bezpieczeństwa (aspekt nigdy nie rzuca wyjątku, nie blokuje żądania), ale jest to realna regresja wprowadzona wprost przez ten refaktor, niepokryta żadnym testem (`CrossTenantAspectTest` sprawdza tylko `assertThatCode(...).doesNotThrowAnyException()`, nigdy nie asercjonuje poziomu/treści loga).
  - **Fix:** rozszerzyć warunek bypassu analogicznie do `isAuthenticationBootstrapMethod` — np. dodać wcześniej w `verifyTenantContext()`: `if ("SUPER_ADMIN".equals(TenantContext.getUserRole())) { log.trace(...); return; }` (rola JEST ustawiona nawet bez tenantId, więc to bezpieczny i tani check). Warto dodać test w `CrossTenantAspectTest` z `TenantContext.setUserRole("SUPER_ADMIN")` (bez `setTenantId`) asercjonujący, że `verifyTenantContext` NIE loguje na poziomie ERROR (np. przez `ListAppender`/`Logback` test appender albo `@Spy` na loggerze).

### ⚠️ Security Concerns

- **`.env.local-demo` (working tree, poza zakresem samego refaktoru ról, ale w tym samym diffie) — realne, wyglądające na wygenerowane sekrety w plaintext w pliku śledzonym przez git, zastępujące wcześniej zredagowane placeholdery `****`.** Plik jest trackowany (`git ls-files .env.local-demo` potwierdza, commit `ddeb9dd`), NIE jest w `.gitignore`, a bieżący diff podmienia `DB_PASSWORD`, `REDIS_PASSWORD`, `RABBITMQ_PASSWORD`, `JWT_SECRET`, `APP_ENCRYPTION_SECRET`, `EMAIL_ENCRYPTION_KEY`, `SOCIAL_TOKEN_ENCRYPTION_KEY` oraz — najbardziej krytyczne — `TWILIO_ACCOUNT_SID`/`TWILIO_AUTH_TOKEN` z placeholderów na wartości wyglądające jak prawdziwe, wygenerowane sekrety (format Twilio SID/Auth Token się zgadza). Jeśli te zmiany trafią do commita (`git add -A`/`git commit -a`), sekrety (w tym potencjalnie realne dane dostępowe do Twilio — ryzyko toll fraud / przejęcia konta) trafią na stałe do historii gita, tym bardziej krytyczne jeśli repozytorium jest publiczne. **Rekomendacja: NIE commitować tego pliku w obecnym stanie.** Przywrócić placeholdery przed commitem (`git checkout -- .env.local-demo` po wydzieleniu realnych wartości do lokalnego, niewersjonowanego pliku), albo rozdzielić na `.env.local-demo.example` (z placeholderami, wersjonowany) + `.env.local-demo` (realne wartości, dodany do `.gitignore`). Jedyna część tego diffu, która faktycznie NALEŻY do refaktoru ról — dodanie `SUPER_ADMIN_EMAIL`/`SUPER_ADMIN_PASSWORD` — jest poprawna i dobrze udokumentowana (komentarz wyjaśnia dlaczego te zmienne są wymagane przy `SPRING_PROFILES_ACTIVE=prod` w `docker-compose.local-demo.yml:15`), ale nie powinna być commitowana razem z resztą plaintext-owych sekretów.
- **`SuperAdminBootstrapRunner`/`UserServiceImpl.createSuperAdminBootstrap` (linia ~547) nie waliduje siły hasła podanego przez `SUPER_ADMIN_PASSWORD` przed hashowaniem.** `validatePasswordStrength()` istnieje i jest używana w `AuthServiceImpl` (zmiana hasła przez usera), ale NIE jest wołana ani tutaj, ani w `UserServiceImpl.createUser`/`AdminUserServiceImpl.createUser` (czyli to spójne z istniejącym wzorcem projektu, nie jest to nowy problem specyficzny dla tego refaktoru) — jednak konto SUPER_ADMIN jest jedynym globalnym, cross-tenant kontem w całym systemie, więc literówka/skrócona wartość w zmiennej środowiskowej `SUPER_ADMIN_PASSWORD` cicho utworzy słabe hasło dla najpotężniejszego konta na platformie bez żadnego ostrzeżenia przy starcie. Komentarz przy `DEV_FALLBACK_PASSWORD` explicite mówi „spełnia wymogi siły hasła projektu” — sugeruje że wymóg istnieje, ale nigdzie nie jest wyegzekwowany dla tej ścieżki. Sugestia: wywołać istniejącą walidację siły hasła w `createSuperAdminBootstrap` (lub w `SuperAdminBootstrapRunner` przed wywołaniem serwisu) i fail-fast (tak jak przy braku zmiennych) gdy hasło nie spełnia wymogów — szczególnie ważne w profilu innym niż dev.
- **Niespójność case-sensitivity w lookupie e-maila SUPER_ADMIN przy logowaniu** (`AppUserRepository.findByEmailAndTenantIdIsNullAndActiveTrue`, derived query — case-sensitive) vs. sprawdzenie flagi `superAdminAccount` na etapie „email-first” (`existsActiveSuperAdminByEmail`, jawnie `LOWER(email) = LOWER(:email)`, case-insensitive). Jeśli użytkownik wpisze e-mail z inną wielkością liter niż zapisana w bazie (np. `SuperAdmin@Firma.pl` zamiast `superadmin@firma.pl`), krok 1 poprawnie zwróci `superAdminAccount: true` (dropdown tenantów zostanie pominięty), ale krok 2 (rzeczywiste logowanie) nie znajdzie użytkownika i zwróci ten sam błąd co złe hasło — mylące, bo UI już „potwierdził” że to konto SUPER_ADMIN. **To jest jednak dokładnie ten sam, już istniejący wzorzec co `findByTenantIdAndEmailAndActiveTrue` używany dla ADMIN/SUPERVISOR/AGENT** (też derived query, też case-sensitive) — nie jest to nowa regresja wprowadzona przez ten refaktor, tylko powielenie istniejącego ograniczenia na nową ścieżkę. Niski priorytet, ale skoro `createSuperAdminBootstrap` jawnie normalizuje e-mail przez `.toLowerCase().trim()` przy zapisie, warto rozważyć zrobienie tego konsekwentnie przy KAŻDYM lookupie e-maila w całym module auth (case-insensitive wszędzie), a nie tylko przy tworzeniu konta.

### 🏗️ Architecture / Pattern Violations

_Brak naruszeń._ Zweryfikowałem systematycznie zgodność z tabelą z sekcji „Backend” planu:
- `/api/admin/**`, `/api/tenants/**` (poza `/{id}/config`), `AdminMetricsController`, `EtlStatusController`, `AuditLogController`, `AdminUserController`, `PluginRevokeController` → `hasRole('SUPER_ADMIN')` wszędzie zgodnie z planem, w tym class-level i method-level adnotacje spójne z `SecurityConfig`.
- `GET`/`PATCH /api/tenants/*/config` → `hasAnyRole('SUPER_ADMIN','ADMIN','SUPERVISOR')` w `SecurityConfig` I w `@PreAuthorize` na obu metodach `TenantController` (uwaga: PATCH wcześniej nie miał WŁASNEJ adnotacji method-level — dziedziczył z class-level `hasRole('ADMIN')`; teraz ma jawną, poprawną adnotację).
- `TenantAiConfigController`, `TenantTwilioConfigController`, `EmailController` (`/config`, `/config/test`), `SocialOAuthController`, `PluginAdminController`, `PluginInvocationLogController`, `PluginUploadController` → `hasRole('ADMIN')` (SUPERVISOR usunięty) — dokładnie 5 obszarów technicznych z planu, ani jednego więcej, ani jednego mniej.
- Kontrolery biznesowe niewymienione w diffie (`CampaignController`, `IvrController`, `QueueController`, `PhoneNumberController`, `PhoneRoutingRuleController`, `CustomerController`, `GdprController`, `RecordingController` itd.) zweryfikowane jako NIETKNIĘTE i nadal `hasAnyRole('ADMIN','SUPERVISOR')` — poprawnie pominięte, zgodnie z planem.
- Kolejność filtrów `JwtAuthFilter → TenantFilter → UsernamePasswordAuthenticationFilter` niezmieniona (`.addFilterBefore(jwtAuthFilter, ...)` + `.addFilterAfter(tenantFilter, JwtAuthFilter.class)`).
- Żaden nowy publiczny endpoint nie został dodany tym refaktorem (kontrakt `/api/public/tenants-by-email` zmienił tylko kształt odpowiedzi) — reguła „dwa miejsca” (`SecurityConfig` + `TenantFilter.PUBLIC_PATH_PREFIXES`, tu: `PublicPathsConfig.PUBLIC_PREFIXES`) nie miała zastosowania, obie listy pozostają zsynchronizowane.
- `TenantContext.setTenantId(null)` rzuciłoby `IllegalArgumentException` — `TenantFilter` poprawnie to omija warunkowym `if (claims.tenantId() != null)`, symetrycznie dla MDC.

### 🔧 Improvements & Suggestions

- **Brak automatycznego testu integracyjnego (Testcontainers/IT) potwierdzającego założenie z komentarza `V080__add_super_admin_role.sql:52-59`**, że produkcyjna rola połączenia `ccapp` ma `BYPASSRLS = TRUE`, a więc RLS na `app_user` (`pol_app_user_select`, `USING tenant_id = current_setting(...)`) nie blokuje w praktyce odczytu wiersza SUPER_ADMIN (`tenant_id IS NULL`) przy logowaniu globalnym. Komentarz mówi „zweryfikowane w pg_roles” — to weryfikacja jednorazowa/ręczna, nieegzekwowana przez CI. Gdyby to założenie było błędne (np. inna konfiguracja na innym środowisku, przyszła zmiana uprawnień roli DB), logowanie SUPER_ADMIN cicho przestałoby działać (`UsernameNotFoundException` zamiast realnego błędu) i żaden istniejący test by tego nie wykrył — wszystkie testy `AuthServiceTest`/`UserDetailsServiceImplTest`/itd. mockują repozytorium, więc nie wykonują żadnego prawdziwego zapytania SQL przez RLS. Sugestia: dodać chociaż jeden IT test (Testcontainers PostgreSQL, migracje aplikowane, użytkownik `SuperAdminBootstrapRunner`-em utworzony) który loguje się jako SUPER_ADMIN end-to-end i potwierdza sukces — obecnie ten najważniejszy nowy przepływ logowania jest zweryfikowany wyłącznie na poziomie jednostkowym z mockami.
- **`SuperAdminBootstrapRunner` — potencjalny (bardzo mało prawdopodobny) race condition przy wielu równoległych replikach startujących jednocześnie po raz pierwszy**: `existsSuperAdmin()` (SELECT) i `createSuperAdminBootstrap()` (INSERT) nie są w jednej transakcji atomowej względem innych instancji aplikacji. Jeśli dwie repliki wystartują dokładnie w tym samym momencie z tym samym `SUPER_ADMIN_EMAIL`, obie przejdą przez `existsSuperAdmin() == false` i obie spróbują utworzyć konto — chroni przed tym `uq_super_admin_email` (INSERT #2 rzuci `DataIntegrityViolationException`), więc nie ma ryzyka duplikatu, ale druga replika zakończy `run()` wyjątkiem zamiast łagodnie się wycofać. Niski priorytet (dotyczy tylko pierwszego uruchomienia od zera, przy kolejnych restartach `existsSuperAdmin()==true` i jest to całkowicie idempotentne), ale warto rozważyć złapanie `DataIntegrityViolationException` w `SuperAdminBootstrapRunner` i potraktowanie go jako no-op (ktoś inny już utworzył konto).

### ✅ Positive Observations

- **Obie ręczne poprawki bezpieczeństwa opisane w briefie są poprawne, null-safe i mają precyzyjne, jawnie nazwane testy regresyjne** (`adminCannotUpdateOtherTenantConfig` z komentarzem „regresja IDOR”, `forcePasswordReset_adminCannotResetOtherTenantUser_crossTenantBlocked`) — dokładnie taki poziom rygoru, jaki oczekuję po security-sensitive fixie w wielotenantowym systemie.
- **`JwtParser`/`JwtService`/`TenantFilter` — wzorowa obsługa opcjonalnego `tenant_id`.** `extractOptionalUuid` odróżnia „claim nieobecny” (zwraca null) od „claim obecny, ale nie jest UUID” (rzuca `JwtValidationException`) — dobre rozróżnienie błędu konfiguracji od stanu oczekiwanego. `JwtService.issueAccessToken` przebudowany na lokalną zmienną `JwtBuilder` żeby warunkowo dodać `tenant_id`/`tenant_name` — czysto, bez duplikacji kodu budowania tokenu. `TenantFilter` ma defensywny check `tenant_id == null && rola != SUPER_ADMIN → 401`, co jest dobrym „belt-and-suspenders” zabezpieczeniem przed nieoczekiwanym stanem tokenu.
- **Defense-in-depth dla tworzenia SUPER_ADMIN**: zarówno `UserServiceImpl.createUser` (tenant-scoped), jak i `AdminUserServiceImpl.createUser`/`updateUser` (cross-tenant) jawnie odrzucają próbę przypisania roli `SUPER_ADMIN` (HTTP 400) mimo że UI i tak nie udostępnia takiej opcji — dokładnie zgodnie z planem, z dedykowanymi testami w obu miejscach (`UserServiceTest`, nowy `AdminUserServiceImplTest`).
- **`PluginRevokeController` — realna spłata udokumentowanego długu technicznego.** Poprzednia wersja komentarza wprost przyznawała się do luki (tenantowy ADMIN mógł globalnie wycofać wersję pluginu innym tenantom) jako świadomej decyzji poza zakresem BE-106. Ten refaktor faktycznie to naprawia (`hasRole('SUPER_ADMIN')`) i komentarz w kodzie to dokumentuje — rzadko widuje się dług techniczny faktycznie spłacony zamiast tylko przeniesiony.
- **`SuperAdminBootstrapRunnerTest` pokrywa dokładnie właściwe przypadki brzegowe**: idempotencja, fail-fast w profilu innym niż dev (zarówno brak obu zmiennych, jak i brak tylko hasła), fallback dev, priorytet zmiennych środowiskowych nad fallbackiem w dev — czytelne, dobrze nazwane `@Nested` klasy.
- **`AuditAspect` już wcześniej poprawnie projektowany pod kątem operacji bez `TenantContext`** (`getTenantIdOrNull()`/`getUserIdOrNull()`, `audit_log.tenant_id` nullable od V004 z komentarzem „NULL dla operacji globalnych”) — dzięki temu `@Audited(action = "SUPER_ADMIN_BOOTSTRAPPED")` na `createSuperAdminBootstrap()`, wywoływane przy starcie aplikacji zupełnie poza kontekstem HTTP, działa bezpiecznie bez żadnej dodatkowej zmiany. Dobry przykład istniejącego kodu, który „po prostu działał” dla nowego przypadku użycia dzięki wcześniej przemyślanemu projektowi.

### Summary

**Ocena: 4/5 ⭐** — bardzo solidny, security-świadomy refaktor z dwiema poprawnie naprawionymi i dobrze przetestowanymi lukami IDOR oraz systematyczną, w pełni zgodną z planem rekonfiguracją `@PreAuthorize`/`SecurityConfig`. Jedna realna, nowa usterka funkcjonalna (fałszywe alarmy ERROR w `CrossTenantAspect` dla każdego żądania SUPER_ADMIN — do naprawienia przed produkcją, bo psuje wartość sygnału bezpieczeństwa tego aspektu) oraz jeden krytyczny, ale niezwiązany bezpośrednio z logiką refaktoru problem operacyjny (plaintext sekrety w trackowanym `.env.local-demo`, który nie powinien zostać zacommitowany w obecnym stanie).

---

## Review: WhatsApp Business — ręczne podłączenie integracji + realna wysyłka (WhatsAppConnectRequest, SocialOAuthController, WhatsAppAdapter, SocialIntegrationDecrypted, SocialIntegrationService/Impl, WhatsAppApiException, GlobalExceptionHandler) — 2026-08-29

Przejrzane pliki:
- `api/social/dto/WhatsAppConnectRequest.java` (nowy)
- `api/social/SocialOAuthController.java` (nowy endpoint `POST /api/integrations/WHATSAPP/connect`)
- `infrastructure/social/WhatsAppAdapter.java` (`sendMessage()` — realna implementacja Graph API)
- `domain/social/SocialIntegrationDecrypted.java` (nowy)
- `domain/social/SocialIntegrationService.java` / `SocialIntegrationServiceImpl.java` (`getDecryptedIntegration()`)
- `domain/exception/WhatsAppApiException.java` (nowy)
- `api/GlobalExceptionHandler.java` (mapowanie `WhatsAppApiException` → 502)
- Kontekst pomocniczy (nie zmieniony w tym diffie, ale konieczny do oceny wpływu): `domain/social/SocialMessageServiceImpl.java`, `domain/social/SocialIntegrationRepository.java`, `db/migration/V010__create_email_social.sql`

Kontekst: moduł ten był już raz recenzowany 2026-04-16 (patrz sekcja „BE-017” wyżej w tym pliku, ocena 2/5, kilka CRITICAL). Część uwag stamtąd została od tego czasu naprawiona (state OAuth faktycznie zapisywany i weryfikowany w Redis, token przy revoke przeniesiony z query stringa do nagłówka `Authorization: Bearer`, `deleteIntegration()` rozbity na 3 etapy żeby nie trzymać HTTP-blocking wywołania w transakcji). Poniższa recenzja dotyczy wyłącznie nowego przyrostu (WhatsApp) i miejsc, w których ten przyrost koliduje z resztą modułu.

### 🐛 Bugs / Critical Issues

- **`WhatsAppAdapter.java:71-81` wywołane z `SocialMessageServiceImpl.java:132-167` — synchroniczne, blokujące wywołanie zewnętrznego Graph API wykonywane WEWNĄTRZ metody `@Transactional`, bez żadnego timeoutu.** `SocialMessageServiceImpl.sendMessage()` jest oznaczone `@Transactional` (linia 132) i w linii 165-167 synchronicznie woła `adapterRegistry.getAdapter(platform).sendMessage(...)`, co dla WHATSAPP trafia teraz do `httpClient.send(request, ...)` w `WhatsAppAdapter.java:81` — realnego wywołania sieciowego do `graph.facebook.com`. `HttpClient` tworzony jest przez `HttpClient.newHttpClient()` (linia 47) bez `connectTimeout`, a `HttpRequest.newBuilder()` (linia 71-78) nie ustawia `.timeout(...)`. To dokładnie ta sama klasa błędu, którą recenzja z 2026-04-16 oznaczyła jako CRITICAL dla `revokeTokenAtProvider()` („blokujące wywołanie HTTP wewnątrz metody `@Transactional` — ryzyko deadlocku puli połączeń” + „HttpClient bez zdefiniowanego connectTimeout”) i którą wtedy naprawiono właśnie przez wyjęcie revoke poza transakcję (`deleteIntegration()`, dzisiejsze 3 etapy) — ale ten sam anti-pattern wraca teraz nowym tylnym wejściem, bo `WhatsAppAdapter` był wcześniej stubem (bez realnego I/O), więc problem był tylko teoretyczny, a ten PR czyni go realnym. Przy spowolnieniu lub niedostępności Graph API (co zdarza się w praktyce) każde wywołanie wysyłki wiadomości WhatsApp trzyma otwarte połączenie z puli HikariCP przez czas nieograniczony — przy kilku równoległych agentach wysyłających wiadomości w tym samym momencie realne ryzyko wyczerpania puli i zawieszenia całej aplikacji (nie tylko modułu social).
  Naprawa: (1) dodać `.connectTimeout(Duration.ofSeconds(5))` do `HttpClient` i `.timeout(Duration.ofSeconds(10))` do `HttpRequest` w `WhatsAppAdapter`; (2) rozdzielić `SocialMessageServiceImpl.sendMessage()` analogicznie do `deleteIntegration()` — najpierw zapisz kontekst potrzebny do wysyłki w krótkiej transakcji, wykonaj wywołanie adaptera POZA transakcją, dopiero potem (poza pierwotną transakcją lub w nowej, krótkiej) zapisz rekord OUTBOUND `SocialMessage`.

### ⚠️ Security Concerns

- **[KRYTYCZNE] Brak weryfikacji, że `phoneNumberId` podany ręcznie przez ADMIN-a nie należy już do INNEGO tenanta — realne ryzyko przejęcia/pomylenia routingu wiadomości przychodzących między tenantami.** `SocialIntegrationServiceImpl.saveIntegration()` (linia 63-64) szuka istniejącej integracji tylko w obrębie *bieżącego* tenanta (`findByTenantIdAndPlatformAndPageId(tenantId, platform, pageId)`) i jeśli nic nie znajdzie — tworzy nowy rekord z dowolnym `pageId` podanym w żądaniu. Jedyny unikalny constraint w DB to `uq_social_integration_tenant_platform_page` **UNIQUE (tenant_id, platform, page_id)** (`V010__create_email_social.sql:253-254`) — a więc NIC nie stoi na przeszkodzie, by dwaj różni tenanci mieli w tabeli `social_integration` dwa różne wiersze z tym samym `platform=WHATSAPP` i tym samym `page_id` (czyli tym samym `phoneNumberId`).
  Dla Facebooka/Instagrama nie było to realnym ryzykiem, bo `pageId` pochodzi z `extractPageIdFromToken()` — w produkcyjnej implementacji z odpowiedzi Graph API na podstawie OAuth tokenu należącego do konkretnego użytkownika Facebooka, więc administrator fizycznie nie mógł podać cudzej strony bez posiadania do niej dostępu OAuth. **Nowy endpoint `POST /api/integrations/WHATSAPP/connect` łamie to założenie** — ADMIN wkleja `phoneNumberId` jako wolny tekst, bez żadnej weryfikacji po stronie serwera, że wklejony `accessToken` faktycznie ma uprawnienia do tego numeru. Wystarczy pomyłka przy kopiowaniu (albo złośliwe działanie administratora innego tenanta), żeby powstał kolizyjny wiersz.
  Konsekwencja: `SocialIntegrationRepository.findByPlatformAndPageId(platform, pageId)` (`SocialIntegrationRepository.java:118`, wywoływana z `SocialMessageServiceImpl.processIncomingMessage()` dla PRZYCHODZĄCYCH wiadomości webhookowych — endpoint bez JWT, identyfikacja tenanta wyłącznie po parze `platform+pageId`) używa `.setMaxResults(1).findFirst()` **bez `ORDER BY`**, więc przy kolizji zwróci nieokreślony jeden z dwóch wierszy. Prawdziwa wiadomość klienta należąca do Tenanta A może trafić (na stałe, dopóki kolizja istnieje) do Tenanta B — pełny wyciek danych/komunikacji między tenantami, dokładnie ten typ luki, przed którym ma chronić cała architektura `TenantAwareRepository`/RLS.
  Co ciekawe, komentarz w `SocialIntegrationRepository.java:108` twierdzi wprost: *„każda strona może należeć tylko do jednego tenanta (constraint `uq_social_integration_platform_page`)”* — ale taki constraint w DB **nie istnieje** (prawdziwa nazwa to `uq_social_integration_tenant_platform_page` i jest ona scoped per-tenant, nie globalna). Ten błędny komentarz sugeruje, że autor kodu webhooka był przekonany o istnieniu ochrony, której nigdy nie było — to prawdopodobnie właśnie dlatego nikt nie dodał odpowiedniej walidacji przy tworzeniu nowego, ręcznego punktu wejścia (`connectWhatsApp`).
  Wymagana naprawa (co najmniej jedna, najlepiej obie): (1) w `saveIntegration()` przed utworzeniem NOWEGO rekordu (branch `orElseGet`) dodać sprawdzenie `repository.findByPlatformAndPageId(platform, pageId)` — jeśli istnieje i należy do INNEGO tenanta niż bieżący, zwrócić `409 Conflict` z komunikatem nieujawniającym nic o tenancie-właścicielu (np. „Ten numer telefonu jest już połączony z inną integracją”); (2) dodać nową migrację (zgodnie z zasadą „nigdy nie edytuj już zastosowanej migracji” — nowy plik `V0xx__social_integration_global_unique_page.sql`) z globalnym unikalnym indeksem częściowym `UNIQUE (platform, page_id) WHERE is_deleted = FALSE` (lub odpowiednik, jeśli tabela nie ma soft-delete — patrz uwaga z 2026-04-16 o braku `is_deleted`) jako zabezpieczenie na poziomie DB, niezależne od poprawności logiki aplikacyjnej; (3) rozważyć realną weryfikację własności numeru w `connectWhatsApp()` przez wywołanie Graph API (`GET /{phoneNumberId}?fields=verified_name` z podanym tokenem) przed zapisem — patrz też sekcja Improvements.

- **`SocialIntegrationDecrypted.java` (record) nie nadpisuje `toString()` — domyślny `toString()` wygenerowany przez Javę wypisze `accessToken` w plaintext, jeśli obiekt trafi kiedykolwiek bezpośrednio do loga.** Javadoc klasy (linie 9-10) wprost ostrzega: *„token... NIGDY nie powinien być logowany w plaintext”*, ale nic w kodzie tego nie wymusza — wystarczy przyszłe `log.debug("integration={}", integration)` (albo np. logowanie wyjątku, które opakuje ten obiekt w komunikat) i token trafi do logów aplikacji. W obecnym diffie `WhatsAppAdapter` faktycznie nie loguje tego obiektu bezpośrednio (dobrze), ale brak jest żadnego mechanizmu obronnego (defense-in-depth) na przyszłość. Ten sam brak istnieje już w analogicznym, starszym `TenantTwilioConfigDecrypted` (`domain/tenant/TenantTwilioConfigDecrypted.java`) — to nie jest regresja wprowadzona przez ten PR, ale skoro to ten sam wzorzec bezpieczeństwa jest teraz powielany po raz drugi, warto to naprawić przy okazji w obu miejscach: `@Override public String toString() { return "SocialIntegrationDecrypted[integrationId=" + integrationId + ", platform=" + platform + ", pageId=" + pageId + ", accessToken=***REDACTED***]"; }`.

### 🏗️ Architecture / Pattern Violations

- **`WhatsAppConnectRequest.java:18-29` — brak `@Size` spójnego z ograniczeniami kolumn DB.** `page_id` i `display_name` w `social_integration` mają `VARCHAR(255)` (`V010__create_email_social.sql:221,224`), ale `phoneNumberId`/`displayName` w DTO mają tylko `@NotBlank`, bez górnego limitu długości. Przesłanie wartości >255 znaków nie zostanie odrzucone przez walidację Bean Validation (422 czytelny dla klienta) tylko przejdzie do Hibernate/PostgreSQL i zakończy się nieobsłużonym `DataException`/500 — niespójne z resztą kontrolera, gdzie walidacja wejścia ma dawać klientowi czytelną, ustrukturyzowaną odpowiedź. Dodać `@Size(max = 255)` na `phoneNumberId`, `displayName` i `businessAccountId`.
- **`SocialIntegrationRepository.java:108`** — nieaktualny/błędny komentarz javadoc odnoszący się do nieistniejącego constraintu `uq_social_integration_platform_page` (prawdziwa nazwa: `uq_social_integration_tenant_platform_page`, i nie jest globalny) — patrz szczegóły w sekcji Security powyżej. Osobno od poprawki logiki, sam komentarz też wymaga sprostowania, żeby nie wprowadzać kolejnych osób w błąd.
- **Anti-pattern „przeciążone kolumny” z CLAUDE.md — NIE dotyczy `platformConfig`.** Sprawdzone celowo na prośbę z briefu: `platform_config JSONB` (`V010__create_email_social.sql:239-240`) to od początku ogólna, jawnie udokumentowana kolumna rozszerzeń per-platforma („Konfiguracja per-platforma, np. Webhook verify token dla FB”), a nie pole o innym, konkretnym przeznaczeniu semantycznym reużyte do czegoś innego. Przechowanie w niej `businessAccountId` dla WhatsAppa (`SocialOAuthController.buildWhatsAppPlatformConfig()`) jest zgodne z pierwotnym przeznaczeniem kolumny i nie jest tym samym co przykład z CLAUDE.md (`scheduled_callback.customer_id` użyty dla innej encji). To nie jest naruszenie.

### 🔧 Improvements & Suggestions

- **Brak jakiejkolwiek walidacji `phoneNumberId`/`accessToken` względem Graph API przed zapisem integracji.** `connectWhatsApp()` zapisuje dane „w ciemno” — pierwszą realną weryfikacją poprawności pary (token, numer) jest dopiero pierwsza próba wysłania wiadomości przez agenta, która zakończy się dopiero wtedy niejasnym błędem 502. Sugestia: przed `saveIntegration()` wykonać lekkie wywołanie `GET https://graph.facebook.com/v19.0/{phoneNumberId}?fields=verified_name` z podanym tokenem — błąd 401/403 z Graph API powinien od razu przełożyć się na czytelny `422`/`409` z komunikatem „Nieprawidłowy token lub phoneNumberId”, zamiast pozwalać zapisać się integracji, która nigdy nie zadziała. To też naturalnie rozwiązuje część problemu z sekcji Security (token bez uprawnień do danego numeru zostanie odrzucony na etapie podłączania, a nie dopiero przy koincydencji z cudzym `pageId`).
- **Zero pokrycia testami dla całego przyrostu.** Nie znalazłem żadnego pliku testowego dla `SocialOAuthController`, `SocialIntegrationServiceImpl` ani `WhatsAppAdapter` — ani nowego, ani już istniejącego wcześniej (`find ... -iname "*SocialOAuthControllerTest*"` itd. — zero wyników). To dotyczy całego modułu social (nie jest regresją wprowadzoną tym PR-em), ale biorąc pod uwagę, że ten PR dodaje pierwszy w projekcie *rzeczywisty* punkt wysyłki danych wrażliwych (permanentny token Meta) do zewnętrznego świata, brak choćby jednego testu weryfikującego: (a) że `@PreAuthorize("hasRole('ADMIN')")` faktycznie blokuje inne role na `/api/integrations/WHATSAPP/connect`, (b) że `WhatsAppAdapter.buildTextMessageBody()` poprawnie escapuje cudzysłowy/znaki specjalne w treści wiadomości, (c) że `WhatsAppApiException` mapuje się na 502 bez wycieku treści odpowiedzi Graph API do klienta — jest realną luką jakościową przed wdrożeniem na produkcję. Rekomenduję chociaż `@WebMvcTest` dla kontrolera i test jednostkowy adaptera z mockowanym/WireMock `HttpClient`.
- **`WhatsAppAdapter.java:61-67` — załączniki (`attachmentUrls`) są cicho ignorowane** (tylko `log.warn`), a wiadomość tekstowa i tak jest wysyłana dalej. Jeśli `content` jest puste/`null` (agent próbuje wysłać wyłącznie obrazek bez podpisu), `buildTextMessageBody()` wygeneruje `{"text":{"body":null}}`, co Graph API odrzuci z 400 → zmapuje się na 502 z niejasnym komunikatem dla agenta. Sugestia: jawnie odrzucić wywołanie wcześniej (rzucić `WhatsAppApiException`/`IllegalArgumentException` z czytelnym komunikatem „Załączniki nie są obsługiwane w integracji WhatsApp — wyślij wiadomość tekstową”), zamiast pozwalać na wysłanie żądania, które i tak zawsze się nie powiedzie w tym przypadku.
- **`GlobalExceptionHandler.handleWhatsAppApiException()` (linie 588-601)** — poprawnie nie ujawnia treści odpowiedzi Graph API klientowi (tylko `ex.getMessage()`, który zawiera jedynie kod statusu HTTP, nie treść błędu Meta) — dobrze, ale warto to samo zweryfikować dla przyszłych wywołań tego wyjątku z innych miejsc (obecnie tylko `WhatsAppAdapter` go rzuca, ze skonstruowanym komunikatem, więc na razie bezpieczne).

### ✅ Positive Observations

- **Budowanie JSON przez Jackson `ObjectNode` zamiast konkatenacji stringów — potwierdzone w obu nowych miejscach.** `WhatsAppAdapter.buildTextMessageBody()` (linia 122-134) i `SocialOAuthController.buildWhatsAppPlatformConfig()` (linia 388-402) poprawnie budują JSON przez `ObjectMapper`/`ObjectNode`, więc treść wiadomości klienta zawierająca cudzysłowy, backslashe czy znaki Unicode nie może wygenerować nieprawidłowego/wstrzykniętego JSON-a. Dokładnie zgodnie z tym, co obiecują komentarze w kodzie.
- **Token nigdy nie trafia do URL/query string — tylko `Authorization: Bearer` header**, konsekwentnie w `WhatsAppAdapter.java:75` i pozostałych miejscach modułu (naprawiona luka z recenzji 2026-04-16 nie została cofnięta przez ten PR).
- **`getDecryptedIntegration()` poprawnie izolowany per-tenant** — `findByTenantIdAndIntegrationId(tenantId, integrationId)` (wywołane z `TenantContext.getTenantId()`, nie z parametru), więc nie da się odczytać odszyfrowanego tokenu integracji innego tenanta podając cudzy `integrationId`. Prawidłowe użycie 404 (nie 403) dla nieznalezionej integracji — spójne z resztą kontrolerów w tym zakresie.
- **Nowy endpoint poprawnie NIE jest publiczny.** `POST /api/integrations/WHATSAPP/connect` nie występuje ani w `SecurityConfig` (`permitAll`), ani w `PublicPathsConfig.PUBLIC_PREFIXES` — spada więc na domyślną regułę `anyRequest().authenticated()` (`SecurityConfig.java:185`) i jest dodatkowo zabezpieczony przez `@PreAuthorize("hasRole('ADMIN')")` (`EnableMethodSecurity(prePostEnabled = true)` potwierdzone aktywne). Kolejność filtrów `JwtAuthFilter → TenantFilter → UsernamePasswordAuthenticationFilter` nie została naruszona (niezmieniona w tym diffie).
- **Błędy walidacji `@Valid` na `WhatsAppConnectRequest` nie kończą się 500.** Istniejący, generyczny handler `MethodArgumentNotValidException` (`GlobalExceptionHandler.java:72-93`) zwraca ustrukturyzowany `422` z mapą pole→komunikat, budowaną wyłącznie z własnych, statycznych komunikatów `@NotBlank(message=...)` — żadna wartość pola (w tym potencjalnie wklejony `accessToken`) nie trafia do treści błędu.
- **i18n dla nowego formularza kompletne i spójne w 4 językach** (pl/en/de/uk) — wszystkie nowe klucze (`whatsappConnectDialogTitle`, `accessTokenHint` itd.) obecne wszędzie, `accessTokenHint` w każdym języku jawnie ostrzega użytkownika o poufności tokenu.

### Summary

**Ocena: 2.5/5 ⭐** — Warstwa autoryzacji i budowanie JSON są wykonane wzorowo, a część luk z poprzedniej recenzji modułu pozostała naprawiona. Jednak ten przyrost wprowadza jedną realną, krytyczną lukę multi-tenancy (możliwość kolizji `phoneNumberId` między tenantami przy braku globalnej weryfikacji własności numeru — realne ryzyko wycieku wiadomości klienta do cudzego tenanta) oraz odtwarza dokładnie ten sam anti-pattern „blokujące HTTP w transakcji bez timeoutu”, który był już raz krytykowany i naprawiony w tym samym module dla innej metody. Do naprawy przed wdrożeniem na produkcję: kolizja `pageId` między tenantami oraz timeout/rozdzielenie transakcji przy wysyłce wiadomości. Zero testów dla nowego kodu obniża pewność co do pozostałych ścieżek błędów.

---

## Review: BE-125 — usuwanie wiadomości e-mail/social po `contact_id` wraz z obiektami S3 (EPIC-30) — 2026-09-21

**Branch:** `feature/epic-30-message-retention` (zmiany niezacommitowane względem HEAD `078134b`)
**Reviewer:** senior-code-reviewer agent
**Zakres (main):** `domain/email/{EmailAttachmentStorageService, EmailAttachmentStorageServiceImpl, EmailMessageRepository, EmailMessageService, EmailMessageServiceImpl}`, nowe `domain/email/{EmailAttachmentException, EmailAttachmentKeys, PurgedMessages}`, `domain/social/{SocialMessageRepository, SocialMessageService, SocialMessageServiceImpl}`.
**Zakres (test):** `domain/email/{EmailAttachmentKeysTest, EmailAttachmentStorageServiceImplTest, EmailMessageServiceImplPurgeTest, PurgedMessagesTest, EmailMessagePurgeIntegrationTest, EmailMessagePurgeRlsIntegrationTest, EmailAttachmentStorageServiceMinioTest}`, `domain/social/{SocialMessageServicePurgeTest, SocialMessagePurgeIntegrationTest}`, harness `support/{PostgresTestDatabase, JpaTestContext, TestcontainersSupport}`.
**Metoda:** czytanie kodu i testów względem `TASKS-BACKEND.md` (BE-125 wraz z „Korektami z BE-124”, BE-126, BE-127) i `DESIGN-message-retention-and-partitioning.md`; weryfikacja twierdzeń na żywej bazie wyłącznie odczytem (`default_transaction_read_only=on`: `\d email_message`, `\d social_message`, `pg_policies`, `pg_roles`, kształt kluczy w `email_message.attachments`). **Nie uruchamiałem Mavena ani testów** (trwa pełny build), nie pisałem do bazy/MinIO. Wnioski o zachowaniu testów wynikają z lektury kodu i z liczb podanych w notatce wykonawcy — nie z własnego przebiegu.

### Indeks ustaleń (wg wagi)

| ID | Waga | Status | Miejsce | Streszczenie |
|----|------|--------|---------|--------------|
| BE125-01 | major (poza zakresem diffu) | potwierdzone (kod) | `EmailSendServiceImpl.java:326`, `EmailAttachmentController.java:130-131` | `s3Key` z żądania wysyłki nie jest walidowany (cross-tenant attach); kontrola w downloadzie bez odrzutu `..`; znane (DESIGN U18), brak ticketu |
| BE125-02 | minor (rośnie do major, jeśli BE-127 pominie „dangling”) | potwierdzone (luka w kodzie), częstość — hipoteza | `EmailMessageServiceImpl.java:92-93,130-132` | wiadomość dopisana po SELECT nie jest usuwana; po usunięciu kontaktu zostaje sierotą z PII |
| BE125-03 | minor | potwierdzone (kod) | `EmailMessageRepository.java:245-247` (użycie `:185`) | `toUuid(null)` → `IllegalArgumentException`; `AttachmentsRow.contactId` jest udokumentowany jako nullable dla BE-127 |
| BE125-04 | minor | potwierdzone (kod) | `EmailAttachmentStorageService.java:78`, `EmailAttachmentKeys` (package-private) | publiczne `delete(String)` bez kontroli tenanta; allow-lista niedostępna dla wołających spoza pakietu (BE-126 pkt b, BE-129, BE-131) |
| BE125-05 | minor | potwierdzone (asymetria), skutek latentny | `SocialMessageRepository.java:123-142`, `SocialMessageServiceImpl.java:278-283` | social zwraca `int` — ciche „0 pod RLS” nie jest wykrywalne; brak testu RLS dla social |
| BE125-06 | minor | potwierdzone (kod), scenariusz — hipoteza | `EmailMessageServiceImpl.java:200-227`, `S3Config.java:63-68` | bezpiecznik liczy każdą porażkę tak samo; brak budżetu czasu na fazę S3; klient S3 bez własnych timeoutów |
| BE125-07 | minor | potwierdzone | `EmailAttachmentStorageServiceMinioTest.java:74`, `.github/workflows/ci.yml:29-32` | `minio/minio:latest` (ruchomy tag) w teście uruchamianym w CI na każdy push/PR |
| BE125-08 | minor | potwierdzone (kod) | `EmailMessagePurgeRlsIntegrationTest.java:110-139` | test RLS asertuje inwariant, więc po DB-064 przejdzie „na zielono” bez pokrywania ścieżki niepotwierdzonego DELETE |
| BE125-09 | nit | potwierdzone (kod) | `PurgedMessages.java:39-41` | `Set.copyOf` → `contactIdsBlocked().contains(null)` rzuca NPE (pułapka klasy `Map.of().get(null)`) |
| BE125-10 | nit | potwierdzone (kod) | `EmailAttachmentKeys.java:142-147` | wpis z `s3_url`/nietekstowym `s3_key` pomijany bez WARN i licznika — wiersz znika, obiekt może zostać |
| BE125-11 | nit | potwierdzone (kod) | `EmailAttachmentStorageServiceImpl.java:170-175` + `EmailAttachmentKeys.java:83-100,163-174` | nazwa pliku `.`/`..` daje klucz odrzucany przez allow-listę; `forLog` nie filtruje U+2028/bidi |
| BE125-12 | nit | potwierdzone | `PostgresTestDatabase.java:38-55`, `JpaTestContext.java:23-25,64-66` | higiena harnessu: wyciek kontenera po błędzie Flyway, `ddl-auto: none` vs `validate` w aplikacji, `System.out.println` w testach |
| BE125-13 | nit | sugestia | `EmailMessageServiceImpl.java:86-94`, `EmailMessageService.java` | bulk `DeleteObjects`, podział na podpartie, runtime-guard „poza transakcją”, `@Deprecated(forRemoval)` |

### 🐛 Bugs / Critical Issues

_Brak blockerów ani majorów w samym diffie BE-125._ Logika „S3 przed wierszem”, potwierdzanie `DELETE … RETURNING`, allow-lista i izolacja tenantów są poprawne (szczegóły w sekcji „Co sprawdziłem i uznałem za poprawne”). Potwierdzone niedoskonałości:

- **BE125-02 · minor · `EmailMessageServiceImpl.java:92-93` i `:130-132`** — zbiór wiadomości do usunięcia jest ustalony jednorazowo w SELECT (tx1), a `DELETE` w tx2 identyfikuje wiersze po `message_id`. Wiadomość dopisana do kontaktu **po** SELECT nie trafia do `rows`, więc nie jest usuwana i nie blokuje kontaktu (`contactIdsBlocked`).
  Scenariusz: agent odpowiada na starą wiadomość kontaktu kwalifikującego się do purge — `EmailSendServiceImpl.java:102` ustawia `contactId(original.getContactId())`, więc odpowiedź dziedziczy stary kontakt. Jeśli zapis nastąpi między SELECT a `deleteByIds` (okno = czas fazy S3, sekundy do minut przy wielu obiektach), to BE-126 usunie kontakt, a odpowiedź (treść, adresy, `attachments` z kluczami `pending/`) zostanie z `contact_id` wskazującym na nieistniejący kontakt. `email_message.contact_id` nie ma FK (sprawdzone `\d email_message`: jest tylko `fk_email_message_tenant`), więc baza tego nie zablokuje. Jedyną ścieżką sprzątania jest wariant „dangling” (`NOT EXISTS`) z BE-127, który oba tickety opisują jako opcjonalny. Wiersz „przychodzący” nie jest tu ryzykiem (każdy INBOUND tworzy nowy kontakt — `EmailContactCreator#createContact`), ryzyko dotyczy odpowiedzi OUTBOUND na stare wątki. Ten sam wyścig istniał przy `detachContactReferences` (autor notuje to w „Nie zweryfikowane”), ale przy D1 = A skutkiem jest PII poza polityką retencji, nie tylko osierocona referencja.
  Rekomendacja (BE-126, nie BE-125): (a) po `deleteContacts` wykonać drugi, idempotentny `purgeByContactIds` dla usuniętych kontaktów (zwykle no-op; po usunięciu kontaktu i jego wiadomości nic nowego już się do niego nie podepnie) — najtańsze domknięcie okna; albo (b) uczynić wariant „dangling” z BE-127 obowiązkowym. Dodać test charakteryzujący z `FakeS3`, który w trakcie `delete` wstawia wiadomość dla tego samego kontaktu (dziś ten przypadek nie jest opisany żadnym testem).

- **BE125-03 · minor · `EmailMessageRepository.java:245-247` (użycie `:185`)** — `toUuid(Object)` to `value instanceof UUID ? … : UUID.fromString(String.valueOf(value))`; dla `null` daje `UUID.fromString("null")` → `IllegalArgumentException`. Dziś nieosiągalne (`findAttachmentsByContactIds` filtruje `contact_id IN (…)`, więc `row[1]` nigdy nie jest `NULL`), ale `AttachmentsRow#contactId` jest w Javadoc `:130-139` i w `purgeRows` (`:101-102`) jawnie przewidziany jako `null` dla wiadomości osieroconych, a BE-127 dołoży zapytanie w tym samym repozytorium i naturalnie użyje `toUuid(row[1])`. Pierwsza osierocona wiadomość wysadzi cały SELECT. Testy jednostkowe `OrphanRows` konstruują `AttachmentsRow` ręcznie, więc tego nie łapią.
  Rekomendacja: `value == null ? null : …` już teraz (jedna linia) + test na prawdziwej bazie z wierszem `contact_id IS NULL`, gdy BE-127 doda zapytanie.

- **BE125-06 · minor · `EmailMessageServiceImpl.java:200-227`, `S3Config.java:63-68`** — (a) bezpiecznik (`S3_FAIL_FAST_THRESHOLD = 3`) liczy każdą porażkę jednakowo, także stałe błędy per-obiekt (np. 403 AccessDenied, Object Lock/legal hold), więc odróżnia „S3 leży” od „trzy konkretne klucze są nieusuwalne” wyłącznie liczbą; (b) klient `S3Client` jest budowany bez `apiCallTimeout`/`apiCallAttemptTimeout`/polityki ponowień (`S3Config.java:63-68`), czyli obowiązują domyślne SDK (3 próby, timeouty rzędu dziesiątek sekund na próbę — dokładne wartości do potwierdzenia dla użytej wersji SDK); (c) faza S3 nie ma żadnego całkowitego budżetu czasu — wolny, ale „zdrowy” S3 (np. 20 s/wywołanie) daje 200 wywołań ≈ 66 min bez porażek, a więc bez zadziałania bezpiecznika i bez utrwalenia jakiegokolwiek `DELETE` (wiersze znikają dopiero na końcu, `:130-132`).
  Rekomendacja: wliczać do bezpiecznika tylko błędy przejściowe (`SdkClientException`, 5xx, throttling), a 4xx per-klucz traktować jako porażkę tego klucza; dodać limit czasu fazy S3 (np. 60 s → traktuj jak przerwanie) i/lub `apiCallTimeout` na `DeleteObjectRequest` (`overrideConfiguration`); rozważyć podpartie (np. 100 wiadomości: S3 → `DELETE`), żeby postęp był trwały i nie zależał od najwolniejszego ogona.

### ⚠️ Security Concerns

- **BE125-01 · major (poza zakresem diffu; znane — DESIGN U18, BE-124) · `EmailSendServiceImpl.java:326` i `EmailAttachmentController.java:130-131`** — `EmailSendServiceImpl#buildAttachmentPart` pobiera `attachmentStorageService.download(attachment.s3Key())` dla klucza z ciała żądania (`EmailReplyRequest.PendingAttachment#s3Key`, przepisanego bez zmian przez `EmailController#replyToMessage`/`#sendOutboundEmail`) **bez sprawdzenia prefiksu `email-attachments/{tenantId}/`**. Agent tenanta A, znając pełny klucz obiektu tenanta B (lub nagrania `{tenantId}/…mp3`, EML kontaktu), może dołączyć go do wysyłanego maila — cross-tenant disclosure. Ekspozycja jest ograniczona nieodgadywalnością kluczy (UUID tenanta + UUID wiadomości), ale to obejście granicy tenantów w systemie multi-tenant, a nie tylko „defense in depth”. Dodatkowo kontrola IDOR w `EmailAttachmentController.java:130-131` to samo `startsWith(expectedPrefix)` **bez odrzutu segmentów `.`/`..`** — dokładnie ta klasa obejścia, którą nowe `EmailAttachmentKeys#isOwnedByTenant` już zamyka (dla presigned URL wykorzystanie utrudnia normalizacja ścieżki po stronie klienta/podpisu, ale poleganie na tym jest kruche).
  DESIGN U18 mówi „luka poza zakresem EPIC-30, do osobnego ticketu”, lecz `grep` po `TASKS-BACKEND.md` nie znajduje takiego ticketu (`PendingAttachment` występuje tylko w BE-124/BE-125). Rekomendacja: założyć ticket i naprawić przez wywołanie tej samej allow-listy przed `download` (send-path) oraz w `downloadAttachment`; wtedy allow-lista purge zostaje obroną w głąb. `EmailAttachmentKeys` jest teraz jedynym miejscem, które zna schemat kluczy — to naturalny punkt wspólnej walidacji.

- **BE125-04 · minor · `EmailAttachmentStorageService.java:78`** — interfejs (publiczny, wołany spoza pakietu przez BE-126/129/131) wystawia `delete(String s3Key)`, które kasuje **dowolny** obiekt w buckecie; Javadoc `:64-77` słusznie zaznacza, że kontrola tenanta to „odpowiedzialność wołającego”, ale allow-lista siedzi w package-private `EmailAttachmentKeys`, więc wołający spoza `domain.email` nie ma z niej jak skorzystać (notatka wykonawcy pkt 6 to przyznaje: „allow-listę trzeba dodać po stronie wołającego”). Każdy z trzech przyszłych wołających zaimplementuje własną wersję, a BE-131 (sweep obiektów niewskazywanych przez wiersz) będzie kasował klucze pochodzące z listowania bucketu — tam pomyłka prefiksu kasuje cudzy obiekt.
  Rekomendacja: sygnatura `delete(UUID tenantId, String s3Key)` egzekwująca prefiks wewnątrz (albo publiczna, testowana `S3KeyPolicy`), zamiast kontraktu opartego na konwencji.

### 🏗️ Architecture / Pattern Violations

_Brak naruszeń reguł z `CLAUDE.md`._ Repozytoria rozszerzają `TenantAwareRepository`; każda nowa metoda wywołuje `assertSameTenant(tenantId)` przed `setTenantContextInDb(tenantId)` (także przed sprawdzeniem pustej listy w repozytorium); w zmienionym kodzie main nie ma `TenantContext.clear()`/`restore()` (sprawdzone `grep`); kod bez `Map.of().get(null)` w `S3Phase` (`HashMap`/`HashSet`). Odstępstwo od ticketu (orkiestracja w serwisie zamiast w `EmailMessageRepository#purgeByContactIds`) jest uzasadnione i udowodnione testem z pulą rozmiaru 1.

- **BE125-05 · minor · `SocialMessageRepository.java:123-142`, `SocialMessageServiceImpl.java:278-283`** — asymetria kontraktu: e-mail zwraca zbiór potwierdzony przez `DELETE … RETURNING` i `contactIdsBlocked`, social tylko `int` z `executeUpdate()`. Pod rolą bez BYPASSRLS `DELETE` z `social_message` usuwa 0 wierszy bez błędu, a wołający nie ma jak odróżnić „nie było wiadomości” od „RLS nie pozwolił”.
  Realny wpływ **dziś: brak** — sprawdziłem na żywej bazie: `contact` ma polityki tylko `SELECT`/`INSERT`, `email_message` i `social_message` tylko `SELECT`, aplikacja łączy się jako `ccapp` (superuser, `BYPASSRLS`), a pod rolą bez BYPASSRLS także `DELETE` z `contact` usunie 0 wierszy, więc strażnik „brak postępu = koniec pętli” z BE-126 zatrzyma purge, zanim kontakt zniknie.
  Wpływ **latentny**: DB-064 (polityki `ALL` dla `email_message`+`social_message`) i DB-074 (pozostałe tabele, w tym `contact`) są osobnymi ticketami bez wymuszonej kolejności. Jeśli `contact` dostanie politykę DELETE przed `social_message`, kontakty zostaną usunięte, a wiadomości social z PII zostaną po cichu (e-mail jest chroniony przez `contactIdsBlocked`, social — nie). Testy: `SocialMessagePurgeIntegrationTest` nie ma odpowiednika testu RLS z `EmailMessagePurgeRlsIntegrationTest`.
  Rekomendacja: (a) zależność kolejności DB-064 → DB-074 zapisać w tickecie albo (b) dać social ten sam sygnał — np. po `DELETE` jedno tanie `SELECT DISTINCT contact_id … WHERE contact_id IN (…)` (indeks `idx_social_message_contact`) zwracane jako „kontakty z pozostałymi wiadomościami” i włączane do blokad przez BE-126; dodać test RLS dla social.

### 🔧 Improvements & Suggestions

- **BE125-07 · minor · `EmailAttachmentStorageServiceMinioTest.java:74`, `.github/workflows/ci.yml:29-32`** — test startuje `minio/minio:latest`; CI (`mvn -B -pl app -am verify` na `ubuntu-latest`, na każdy push/PR do `main`/`develop`) uruchamia go przy każdym przebiegu. Ruchomy tag + anonimowy pull z Docker Hub = ryzyko czerwonego CI bez zmiany kodu (zmiana CLI/zachowania obrazu, limity pobrań, a także niepewność co do dalszej publikacji obrazów community — do sprawdzenia u dostawcy). Test jest wartościowy (potwierdza 204 dla nieistniejącego klucza), więc nie proponuję jego usunięcia: przypiąć tag `RELEASE.…` (ewentualnie digest) w teście i w `docker-compose.yml`, opcjonalnie oznaczyć `@Tag("minio")` i wyłączyć z profilu CI, jeśli pobrania zaczną się gubić. Zależność od Dockera jest zgodna z praktyką repozytorium (10 istniejących klas wg `grep` po „Testcontainers” — m.in. `CampaignContactArchivePurgeTenantIsolationTest`, `PartitionMaintenanceRepositoryTest` — żadna z guardem `disabledWithoutDocker`/`assumeTrue`) — to nie regresja, tylko rosnąca liczba testów wymagających Dockera.

- **BE125-08 · minor · `EmailMessagePurgeRlsIntegrationTest.java:110-139`** — test celowo asertuje inwariant (`deletedRows == before − after`, `blocked == kontakty z pozostałymi wiadomościami`), więc po DB-064 (gdy `DELETE` zacznie działać) przejdzie „na zielono” z `deletedRows = 3`, `blocked = ∅` i przestanie pokrywać ścieżkę „niepotwierdzony DELETE” — bez żadnego sygnału. Dziś działa (bez tej logiki `deletedRows` = 3 zamiast 0 i test pada), ale reguła „każdy test ma jasno nazwane zjawisko, które sprawdza” jest tu spełniona tylko dopóki polityki się nie zmienią.
  Rekomendacja: dopisać drugi test z `assumeTrue(brak polityki DELETE na email_message)` i twardą asercją `deletedRows == 0`, żeby DB-064 świadomie go zaktualizował; ścieżkę niepotwierdzonego wiersza pokrywa już test jednostkowy z mockiem `deleteByIds` (`EmailMessageServiceImplPurgeTest#DeleteConfirmation`).

- **BE125-09 · nit · `PurgedMessages.java:39-41`** — `Set.copyOf` zwraca niemodyfikowalny zbiór, którego `contains(null)` rzuca `NullPointerException` (to samo, co pułapka `Map.of().get(null)` z `DESIGN` WP-1). BE-126/127 będą mieć w rękach `contactId` mogące być `null` (wiadomości osierocone) i naturalnie napiszą `blocked.contains(row.contactId())`. Rekomendacja: `Collections.unmodifiableSet(new HashSet<>(…))` albo dopisek w Javadoc rekordu.

- **BE125-10 · nit · `EmailAttachmentKeys.java:142-147`** — wpisy niebędące obiektami logują WARN, ale wpis-obiekt z `s3_url` (legacy z komentarza V010) albo z nietekstowym/niepustym-innym-niż-tekst `s3_key` jest pomijany **bez śladu** („nic do usunięcia w S3”). Dla pustego `""` to poprawne (`buildAttachmentsJson` zapisuje `""` przy `s3Key == null`), ale `s3_url`/`s3_key: 5` to anomalia: wiersz zostanie usunięty, obiekt (jeśli istnieje) osierocony bez licznika i logu. Zweryfikowałem żywą bazę: 14/14 wpisów `attachments` ma tekstowy `s3_key` (6 INBOUND, 8 OUTBOUND), więc dziś bez skutku; dla prod nie wiem. Rekomendacja: WARN z `messageId` i licznik (`s3Unresolvable`) dla wpisów z polami spoza `{filename, content_type, size_bytes, s3_key}` lub z `s3_key` niebędącym tekstem.

- **BE125-11 · nit · `EmailAttachmentKeys.java:83-100,163-174`, `EmailAttachmentStorageServiceImpl.java:170-175`** — (a) `encodeFilename` używa `URLEncoder`, który nie koduje kropek, więc nazwa załącznika dokładnie `.` lub `..` (kontrolowana przez nadawcę INBOUND) daje klucz `…/{messageId}/..`, który allow-lista słusznie odrzuca — obiekt nigdy nie zostanie usunięty przez purge (wiersz tak). Wpływ znikomy, ale to jedyny znany sposób na klucz „własny”, a nieusuwalny; wystarczy zamieniać nazwę `.`/`..` na `attachment` w `encodeFilename`. (b) `forLog`/`isOwnedByTenant` filtrują `isISOControl`, nie filtrują U+2028/U+2029 ani znaków sterujących kierunkiem (bidi) — możliwe „spoofing” wiersza w logach tekstowych; marginalne.

- **BE125-12 · nit · harness `support/` i testy** — (a) `PostgresTestDatabase.java:38-55`: gdy `Flyway.migrate()` rzuci, wystartowany kontener nie jest ani zatrzymany, ani zapamiętany, więc każda kolejna klasa testowa startuje i niszczy kolejny (wolno; przy `TESTCONTAINERS_RYUK_DISABLED=true` wyciek) — złapać wyjątek i `started.stop()`, ewentualnie shutdown hook; (b) `JpaTestContext.java:23-25,64-66`: Javadoc twierdzi „jak w aplikacji: `ddl-auto: none`”, ale `application.yml:32` ma `validate` (`none` jest tylko w `application-test.yml`); `validate` w harnessie łapałoby rozjazd encja↔schemat Flyway; (c) `System.out.println` w `EmailMessagePurgeIntegrationTest.java:539-540`, `EmailMessagePurgeRlsIntegrationTest.java:137`, `SocialMessagePurgeIntegrationTest.java:259`, MinIO: szum w logach CI — użyć loggera; (d) singleton kontenera bez sprzątania danych jest bezpieczny tylko dzięki losowym tenantom (obecnie zachowane w każdym teście).

- **BE125-13 · nit · `EmailMessageServiceImpl.java:86-94`** — opcje na później: (a) `DeleteObjects` (do 1000 kluczy/żądanie) zamiast sekwencyjnych `DeleteObject` — przy założeniu autora 20–50 ms/wywołanie na AWS (założenie, nie pomiar) to ≈ 4–10 s na 100 kontaktów × 2 załączniki, dla zaległości rzędu 10⁶ wiadomości godziny; warto zmierzyć na docelowym S3 przed BE-126; (b) guard w czasie wykonania na prekontrakt „poza transakcją” (`TransactionSynchronizationManager.isActualTransactionActive()` → WARN/wyjątek) — dziś to tylko Javadoc, a przyszłe owinięcie `purgeContactInteractions` w `@Transactional` po cichu przypnie połączenie na czas I/O (test z pulą 1 sprawdza wyłącznie wywołanie bezpośrednie); (c) `@Deprecated(since = "…", forRemoval = true)` dla `detachContactReferences` — widoczniejsze ostrzeżenie kompilatora do czasu BE-126 (`RetentionPurgeServiceImpl` nadal woła oba warianty, więc dziś to zwykłe ostrzeżenia).

### 🔍 Hipotezy do sprawdzenia (niepotwierdzone)

- **H-1 · head-of-line blocking przy stałych porażkach (BE-126/BE-127)** — `contactIdsBlocked` chroni dane, ale nie odróżnia porażki przejściowej od stałej. Jeśli `findContactIdsOlderThan` zwraca `ORDER BY started_at, contact_id` (jak wymaga BE-126), to kontakty zablokowane stałą porażką (np. obiekt pod legal hold/AccessDenied) zawsze leżą na początku kolejnej paczki; przy ≥ `batch-size` takich kontaktów purge tenanta stoi w miejscu — pętla kończy się poprawnie („brak postępu”), ale nigdy nie dociera do młodszych kontaktów. Analogicznie dla BE-127 (`deletedRows` jako miara postępu) z osieroconymi wiadomościami. Do sprawdzenia w BE-126/127: kursor keyset (ostatni `(started_at, contact_id)`) zamiast „zawsze od początku”, albo wykluczanie zablokowanych w bieżącym przebiegu; test z ≥ `batch-size` trwale nieusuwalnymi kontaktami. Dodatkowo pętla w BE-126 powinna kontynuować wg `ids.size() == batch`, a nie wg liczby faktycznie usuniętych kontaktów (zablokowane skracają tę liczbę i kończyłyby pętlę przedwcześnie).
- **H-2 · semantyka `DeleteObject` na docelowym S3** — `DeleteObject` bez `versionId` w buckecie z wersjonowaniem tylko dodaje delete marker (dane zostają), a przy Object Lock zwraca błąd. `DEPLOYMENT.md` §21 (`:1209-1211`) opisuje prod MinIO bez wersjonowania, ale z globalnym `mc ilm add … --expiry-days 365` na całym buckecie `contact-center-recordings` — tym samym, do którego `EmailAttachmentStorageServiceImpl` zapisuje `email-attachments/`. Skutki do potwierdzenia z właścicielem: (1) w produkcyjnym buckecie nie może być włączone wersjonowanie/Object Lock, inaczej purge nie usuwa PII z załączników; (2) czas życia obiektów (365 dni, globalnie) jest niezależny od retencji tenanta (`CONTACT_INTERACTIONS`, np. 24 mies.) — załączniki znikną, zanim purge usunie wiersze; `delete` traktuje brak obiektu jako sukces, więc nie szkodzi to purge, ale użytkownik zobaczy martwe linki. Poza zakresem BE-125; do decyzji w BE-131.

### ✅ Co sprawdziłem i uznałem za poprawne

- **„S3 przed wierszem” (`EmailMessageServiceImpl.java:104-159`)** — wiersz trafia do `deletable` tylko gdy każdy jego klucz został usunięty albo świadomie odrzucony przez allow-listę; awaria S3 zostawia wiersz z nietkniętym JSONB (test `jsonb_array_length == 2` po porażce), drugi przebieg jest idempotentny (klucze usuwane ponownie, wynik pełny). Brak utraty PII: PII znika wyłącznie razem z wierszem, wiersz znika wyłącznie po obiektach (poza kluczem obcym — wtedy PII znika, cudzy obiekt zostaje, zgodnie z korektą (b)). Kierunek „obiekt usunięty, a wiersz zostaje” (gdy `DELETE` padnie/niepotwierdzony) jest akceptowalny: wiadomość i tak jest w zbiorze do usunięcia, a kolejny przebieg jest idempotentny; jedyny szkodliwy wariant to klucz współdzielony z wiadomością spoza partii — przyjęty w tickecie (pkt e), zachowanie zgodne (jeden `DeleteObject` na klucz, jeden wynik dla wszystkich wskazujących wiadomości).
- **Brak `@Transactional` w serwisie** — uzasadniony i wystarczający: dwie krótkie transakcje repozytorium (`readOnly` SELECT, `DELETE … RETURNING`), między nimi I/O bez połączenia (test z pulą 1: aktywne połączenia w chwili wywołania S3 = 0). `RetentionPurgeServiceImpl#purgeAsync` (`:124-155`) nie jest transakcyjne, więc BE-126 może wołać serwis bezpośrednio. Jedyna luka to okno SELECT→DELETE (BE125-02).
- **`contactIdsBlocked`** — obejmuje porażkę S3, przerwaną fazę (niepróbowane klucze zwracają `false`) i wiersz niepotwierdzony przez `RETURNING` (ochrona przed cichym „0 usuniętych”: potwierdzone na żywej bazie, że `email_message` ma wyłącznie politykę `SELECT`, a aplikacja działa jako superuser `ccapp` z `BYPASSRLS`, więc demo tego zjawiska nie pokaże — test pod rolą `NOBYPASSRLS` jest właściwym dowodem). Wiadomości `contactId == null` nie trafiają do zbioru (unit test `OrphanRows`). `Set.copyOf` nie dostaje `null` (dodawane są tylko niepuste `contactId`).
- **Allow-lista `isOwnedByTenant` (`EmailAttachmentKeys.java:83-100`)** — prefiks sprawdzany razem z końcowym `/` przy stałej długości UUID, więc „UUID jako prefiks innego” nie przechodzi (test `prefixMustEndAtSegmentBoundary`); `startsWith` jest rozróżniające wielkość liter, a `UUID#toString()` daje małe litery, więc błąd kończy się odrzuceniem (fail-closed); segmenty `.`/`..` (w tym końcowe) odrzucane; znaki sterujące (w tym `\0`, `\n`) odrzucane; sam prefiks, `null`, pusty klucz — odrzucone; nazwa `archive..tar.gz` przechodzi poprawnie. Klucze zbudowane przez `inboundKey`/`pendingKey` przechodzą własną allow-listę (jedno źródło prawdy, test regresji schematu). Sprawdzone na żywych danych: wszystkie 14 wpisów `attachments` ma `s3_key` z prefiksem własnego tenanta, bez `..` i bez znaków sterujących — brak fałszywych odrzuceń na danych demo.
- **`forLog`** — zastępuje znaki sterujące i przycina do 200 znaków (limit liczony w `char`, więc para zastępcza na granicy może zostać rozcięta — kosmetyka); wszystkie logi klucza w `EmailMessageServiceImpl`/`EmailAttachmentStorageServiceImpl` idą przez `forLog`; `e.getMessage()` z SDK nie zawiera treści kontrolowanej przez klienta.
- **SQL/JPA** — zapytania natywne z parametrami wiązanymi (brak konkatenacji), `CAST(:tenantId AS uuid)`, `IN (:list)` z `List<UUID>` dzielone po 1000 (≪ 32767), `RETURNING` przez `getResultList()`, projekcja `Object[]` zamiast `createNativeQuery(sql, EmailMessage.class)` (omija pułapkę `resultClass`+enum z EPIC-29), `CAST(attachments AS text)`; brak `ctid` (test regexem `\bctid\b`, poprawnie z granicą słowa); filtr `tenant_id` jako bariera obok RLS. Indeksy istnieją na żywej bazie: `idx_email_message_contact (contact_id, received_at DESC)`, `pk_email_message (message_id)`, `idx_social_message_contact (contact_id, sent_at DESC)`; `email_message.attachments` ma `CHECK jsonb_typeof = 'array'`, więc gałąź „korzeń niebędący tablicą” jest tylko defensywna. Uwaga o BE-134/DB-067 (klucz złożony, `AttachmentsRow` musi nieść `messageAt`; `deletable` kluczowane samym `messageId`) jest w kodzie odnotowana i poprawna.
- **Multi-tenancy / `TenantContext`** — patrz sekcja „Architecture”; test niezgodnego tenanta potwierdza, że przy `CrossTenantAccessException` S3 nie jest ruszane (SELECT rzuca przed fazą S3), a test z pustym kontekstem — `IllegalStateException` z serwisu i obu metod repozytorium.
- **Testy** — asercje po wartościach (COUNT/ID, `new PurgedMessages(…)` w całości), izolacja tenantów także przy TYM SAMYM `contact_id` u obu tenantów, granica porcji `IN` (2345 kontaktów/1205 wiadomości), pula rozmiaru 1 jako dowód braku połączenia w trakcie S3, rola `NOBYPASSRLS` jako dowód „cichego 0”, `EXPLAIN` dokładnie tych stałych SQL, test MinIO potwierdzający 204 dla nieistniejącego klucza i pomiar opóźnienia bez asercji czasowych (nie flaky). Harness: kontener singleton per JVM jest stabilny przy domyślnym, sekwencyjnym Surefire (brak `parallel`/`forkCount`/`junit-platform.properties` — sprawdzone), testy nie zależą od kolejności (losowi tenanci; testy `EXPLAIN` sprzątają w `finally`); ryzyko flaky w testach `EXPLAIN` jest niskie (jawny `ANALYZE`, wyszukiwane 3 klucze na 30 tys. wierszy), ale niezerowe przy współdzielonej tabeli.

### Kontrakt dla BE-126 i BE-127 — czy wystarczający?

**Tak dla BE-126 i BE-127 — z poprawkami po ich stronie.** `deletedRows` (potwierdzone `RETURNING`) jest właściwą miarą postępu, `contactIdsBlocked` chroni przed utratą wskaźnika, liczniki `s3*` pasują do `breakdown`, `plus()` sumuje partie, `purgeRows` jest gotowe do reużycia przez BE-127 (osierocone `contactId = null` nie wpływają na blokady). Do uwzględnienia: BE125-02 (drugi przebieg po `deleteContacts` lub obowiązkowe „dangling”), H-1 (kursor zamiast „zawsze od początku”, pętla wg `ids.size() == batch`), BE125-05 (0 z social nie jest sukcesem — polegać na strażniku postępu po stronie `contact`; ustalić kolejność DB-064 → DB-074), BE125-09 i BE125-03 (`null` w `contactIdsBlocked`/`toUuid` dla BE-127), BE125-04 (BE-126 pkt b: usuwanie `recording_url` wymaga allow-listy `{tenantId}/…` po stronie wołającego — najlepiej w metodzie `delete(tenantId, key)`).

### Uwagi poza zakresem diffu

- `git status` pokazuje zmodyfikowany `.env.local-demo`; jego diff zastępuje placeholdery `***` wartościami wyglądającymi na prawdziwe sekrety (hasła DB/Redis/RabbitMQ, `JWT_SECRET`, klucze szyfrujące, Twilio SID/token — wartości nie kopiuję). Ten plik jest śledzony w git — **nie może trafić do commita** razem z tą zmianą (zgodnie z uwagą zlecającego o wyłączeniu go z zakresu; przypominam tylko dlatego, że zmiany BE-125/DB-079 czekają na commit). Nieśledzony `voicebot/app/__pycache__/` też nie powinien trafić do commita.

### Summary

**Ocena: 4/5 ⭐** — Rdzeń (kolejność S3 → wiersz, potwierdzanie `DELETE … RETURNING`, allow-lista tenanta, izolacja i testy na prawdziwej infrastrukturze) jest solidny, dobrze udokumentowany i lepszy niż wymagał ticket; nie znalazłem blockerów ani majorów w samym diffie. Do poprawy przed/w BE-126: okno wyścigu SELECT→DELETE (BE125-02), latentny `toUuid(null)` (BE125-03), publiczne `delete` bez kontroli tenanta (BE125-04) i asymetria social (BE125-05); niezależnie od tego luka walidacji `s3Key` w ścieżce wysyłki (BE125-01) wymaga własnego ticketu. **Werdykt: zatwierdzić z poprawkami** (nic nie blokuje scalenia BE-125; poprawki BE125-03/09 są jednolinijkowe i najlepiej zrobić je teraz).

---

## Review: BE-126 — integracja usuwania wiadomości w `RetentionPurgeServiceImpl#purgeContactInteractions` (EPIC-30) — 2026-09-22

**Branch:** `feature/epic-30-message-retention` (niezacommitowane względem HEAD `545c6e9`)
**Reviewer:** senior-code-reviewer agent
**Zakres:** `domain/retention/{RetentionPurgeServiceImpl, RetentionPurgeLogRepository}`, `domain/contact/{ContactRepository, ContactService, ContactServiceImpl}`, nowy `domain/contact/ContactPurgeCandidate`, `application.yml` (flaga `retention.purge.delete-messages`, l. 337-344).
**Zakres (test):** nowy `domain/contact/ContactRepositoryPurgeCandidatesIntegrationTest` (Testcontainers), zmieniony `domain/retention/RetentionPurgeServiceImplTest` (+8 testów: `MessageDeletionEnabled`, `FlagDisabledRegression`).
**Metoda:** czytanie kodu i testów względem `TASKS-BACKEND.md` (BE-126, obie notatki „Uwaga z BE-125"/„Uwaga z code review BE-125" i notatka wykonawcy 2026-09-22) i `DESIGN-message-retention-and-partitioning.md`; `git show 545c6e9:...`/`git diff` do potwierdzenia bajt-w-bajt ścieżki legacy; weryfikacja na żywej bazie wyłącznie odczytem (`PGOPTIONS='-c default_transaction_read_only=on'`: `\d contact`, `\d retention_purge_log`, `pg_roles`, `EXPLAIN` dla `findContactIdsOlderThan`/`deleteContacts`/legacy `deleteBatchOlderThan`). **Nie uruchamiałem Mavena** (trwa równoległy pełny build zlecającego), nie pisałem do bazy/MinIO.

### Indeks ustaleń (wg wagi)

| ID | Waga | Status | Miejsce | Streszczenie |
|----|------|--------|---------|--------------|
| BE126-01 | major (latentne, dziś bez wpływu) | potwierdzone w kodzie; wyzwalacz — hipoteza | `RetentionPurgeServiceImpl.java:328,349-354` | strategia H-1 (kursor keyset) usunęła niezmiennik „postęp=0 → FAILED" z BE-124; częściowa cicha porażka `deleteContacts` (RLS bez `BYPASSRLS`) nie loguje NIC i kontakt jest tracony na zawsze (kursor już go minął) |
| BE126-02 | minor | potwierdzone (`EXPLAIN` na żywej bazie) | `ContactRepository.java:707-712,744-772` | `deleteContacts` gubi `startedAt` już policzone przez wywołującego — DELETE skanuje wszystkie 11 partycji zamiast tylko starych (identycznie jak `deleteBatchOlderThan`, więc NIE regresja, ale tania okazja do poprawy przegapiona) |
| BE126-03 | minor | potwierdzone | `dto/PurgeResultDto.java:28` | Javadoc `errorMessage` wciąż mówi „przy status=FAILED" — BE-126 legalnie zapisuje niepusty `errorMessage` też dla `COMPLETED`; kontrakt DTO (konsument: przyszłe FE-110) jest teraz błędnie opisany |
| BE126-04 | nit | potwierdzone | `TASKS-BACKEND.md:7227,7240` | notatka wykonawcy liczy „12 nowych testów"/„13 testów" — faktycznie +8 (`git diff` 30→38 `@Test`) i 12; nie wpływa na kod, tylko na rzetelność notatki |
| BE126-05 | — (informacyjne) | potwierdzone | `TASKS-BACKEND.md:7236,7244` | uwaga kontraktowa BE125-04→BE-143 w sekcji BE-126 nadal ma stare, przyszłościowe sformułowanie („decyzja zapada w BE-143") mimo że BE-143 już ją podjęła (`isRecordingKeyOwnedByTenant`) — do ręcznego dogrania, sam wykonawca BE-143 to zgłasza jako lukę procesu |

### 🐛 Bugs / Critical Issues

_Brak potwierdzonych blockerów/bugów w diffie._ Poniżej jedno ustalenie o wadze major, ale **latentne i dziś bez wpływu** (patrz uzasadnienie) — dokumentuję je szczegółowo, bo dotyczy bezpośrednio strategii H-1, którą wykonawca sam wybrał jako świadome odejście od specyfikacji ticketu, i warto, żeby trade-off był zapisany, zanim ktoś o nim zapomni.

- **BE126-01 · major (latentne) · `RetentionPurgeServiceImpl.java:294-370`, konkretnie `:328` (`deleteContacts`) i `:349-354` (WARN)** — Ticket BE-124/BE-125 definiował niezmiennik pętli jako „postęp = liczba faktycznie usuniętych wierszy `contact` w iteracji > 0" właśnie po to, by wykryć scenariusz „`DELETE` z `contact` cicho usuwa 0 wierszy pod rolą bez `BYPASSRLS` bez polityki DELETE" (dosłowny cytat z `TASKS-BACKEND.md:7235`: „strażnik pętli zatrzymuje purge"). Wykonawca BE-126 **świadomie zastąpił ten strażnik** stronicowaniem keyset, żeby rozwiązać H-1 (head-of-line blocking) — słuszna, dobrze przetestowana decyzja (`HeadOfLineBlockingDefense`). Efekt uboczny, którego nie widzę nigdzie opisanego: kursor (`cursor = page.get(page.size() - 1)`, `:358`) przesuwa się o CAŁĄ stronę **bezwarunkowo**, niezależnie od tego, ile kontaktów faktycznie usunął `deleteContacts` — WARN (`:349-354`) loguje się WYŁĄCZNIE gdy `actuallyDeletedContacts.isEmpty()` (cała strona = 0), nie gdy jest częściowo mniejszy niż `deletableIds` (np. 60 z 100 żądanych). Scenariusz: gdyby w przyszłości `contact` dostał politykę row-level-security ograniczającą `DELETE` (DB-074) i aplikacja przestała łączyć się z `BYPASSRLS` (dziś: `ccapp`, `rolsuper=t, rolbypassrls=t` — potwierdzone na żywej bazie), a polityka z jakiegoś powodu odrzuciłaby DELETE dla PODZBIORU kontaktów strony (np. wiersz przypisany innej roli aplikacyjnej, przyszła wielo-rolowa architektura) — te kontakty znikają z widoku pętli NA ZAWSZE (SELECT następnej strony zaczyna się ściśle po ostatnim kandydacie tej strony), bez ŻADNEGO logu, mimo że ich wiadomości mogły już zostać skutecznie usunięte przez wcześniejszą fazę „dzieci" (bo nie były w `contactIdsBlocked`). To nie psuje PII dziś (kontakt zostaje w bazie, nie znika żadna ochrona danych), ale cicho łamie sam cel retencji (`contact` nigdy nie zostanie wyczyszczony) i jest niewykrywalne bez ręcznego porównania `COUNT` przed/po.
  Odróżnienie od BE125-05 (social): tamto ustalenie mówiło o social messages i zakładało, że „strażnik pętli" wciąż istnieje jako siatka bezpieczeństwa dla CAŁEJ operacji — ten strażnik został usunięty w BE-126 właśnie dla kontaktów, więc założenie z notatki BE-125 (`TASKS-BACKEND.md:7235`) jest już nieaktualne względem tego, co faktycznie wdrożono.
  Rekomendacja (nieblokująca, bo dziś nieosiągalne): (a) dopisać do Javadoc `purgeContactInteractionsWithMessageDeletion` jawne zdanie o tym trade-offie (H-1 kontra „zero cichych strat"), żeby przyszły czytelnik nie założył, że stary niezmiennik nadal obowiązuje; (b) zmienić warunek loga z `actuallyDeletedContacts.isEmpty()` na `actuallyDeletedContacts.size() < deletableIds.size()`, żeby częściowa cicha strata była chociaż widoczna w logach (koszt: jedno porównanie rozmiarów, już oba zbiory są w pamięci).

### ⚠️ Security Concerns

_Brak nowych ustaleń bezpieczeństwa w tym diffie_ — multi-tenancy jest zachowana (`assertSameTenant` + `setTenantContextInDb` przed każdym zapytaniem w `ContactRepository#findContactIdsOlderThan`/`#deleteContacts`, potwierdzone testami izolacji tenantów `tenantIsolation_sharedPartition`/`tenantIsolation_foreignIdIsIgnored` na prawdziwej bazie), a `deleteContacts` filtruje po `tenant_id` w WHERE niezależnie od RLS (spójne z resztą repozytorium, gdzie RLS jest warstwą drugą, nie jedyną — aplikacja łączy się jako `ccapp`/`BYPASSRLS`).

### 🏗️ Architecture / Pattern Violations

_Brak naruszeń reguł z `CLAUDE.md`._ `ContactRepository` rozszerza `TenantAwareRepository`; obie nowe metody wywołują `assertSameTenant(tenantId)` PRZED `setTenantContextInDb(tenantId)` (`ContactRepository.java:681-682,748-749`), zgodnie z konwencją reszty klasy. Brak nowych publicznych endpointów (zmiana wyłącznie w warstwie serwis/repozytorium). `TenantContext.snapshot()`/`restore()`/`clear()` w `purgeAsync` (`:142,197`) jest niezmieniony względem stanu sprzed BE-126 (potwierdzone `git diff` — te linie nie są w diffie) i poprawnie obejmuje też nową ścieżkę (test `TenantContextOnNewPath.tenantContext_isClearedAfterCompletion`, `RetentionPurgeServiceImplTest.java:1112-1123`). `RetentionEvaluationServiceImpl#maybeTriggerAutoPurge` nie jest częścią diffu i nie było potrzeby go zmieniać — ustawia `TenantContext.setTenantId`/`clear()` wokół wywołania `retentionPurgeService.purge()` (synchronicznego), które z kolei robi `snapshot()` przed dyspatchem do `self.purgeAsync` — łańcuch kontekstu jest kompletny i niezmieniony.

- **BE126-02 · minor · `ContactRepository.java:707-712` (`DELETE_CONTACTS_SQL`), `:744-772` (`deleteContacts`)** — `deleteContacts` identyfikuje wiersze wyłącznie przez `contact_id IN (...)`, mimo że wywołujący (`RetentionPurgeServiceImpl:312,323-325`) ma w ręku `ContactPurgeCandidate.startedAt()` dla każdego ID (z tej samej strony `findContactIdsOlderThan`) i odrzuca je budując `deletableIds` jako `List<UUID>`. Zweryfikowałem `EXPLAIN` na żywej bazie: `DELETE FROM contact WHERE tenant_id=... AND contact_id IN (...)` generuje `Append` po WSZYSTKICH 11 partycjach (`contact_2026_03` … `contact_2026_12`, `contact_default`), bo `contact_id` nie jest kolumną partycjonowania (`RANGE (started_at)`) i planer nie ma jak przyciąć. To **nie jest regresja** — `deleteBatchOlderThan` (ścieżka legacy, niezmieniona) robi dokładnie to samo mimo że NIESIE `started_at` w joinie z CTE `batch` (zweryfikowane osobnym `EXPLAIN`: też `Append` po 11 partycjach, bo wartości `started_at` w `batch` są różne dla różnych wierszy i nie są znane w czasie planowania) — więc obie ścieżki mają ten sam profil kosztowy, zgodnie z Javadoc autora („akceptowalne dla rozmiarów batcha retencji ≤ kilkaset wierszy"). Natomiast `findContactIdsOlderThan` SAMO w sobie JEST efektywnie przycinane (potwierdzone `EXPLAIN`: `started_at < :cutoff` jako stała/bind ogranicza `Append` do partycji faktycznie starszych niż cutoff, np. 4 z 11 dla `cutoff=2026-06-01`) — więc mechanizm przycinania partycji po `cutoff` już działa i jest tanio dostępny, tylko `deleteContacts` z niego nie korzysta.
  Rekomendacja (nieblokująca, okazja do poprawy, nie naprawa regresji): dodać `cutoff` (ten sam, który już zna wywołujący) jako dodatkowy warunek `AND started_at < :cutoff` do `DELETE_CONTACTS_SQL` — bezpieczne (wszystkie `ids` pochodzą z `findContactIdsOlderThan` tego samego przebiegu, więc warunek jest zawsze prawdziwy dla legalnych wywołań) i pozwoliłoby planerowi ograniczyć `Append` do tych samych ~4 partycji zamiast 11, bez zmiany kontraktu metody (`ids` nadal wystarcza do identyfikacji wierszy, `cutoff` tylko przycina zakres przeszukiwania). Przy partiach rzędu 100-1000 i ~12 partycjach dziś koszt jest niski, ale rośnie liniowo z liczbą przechowywanych miesięcy/partycji, a purge jest operacją uruchamianą regularnie na dużej historii — tania poprawka o rosnącej wartości w czasie.

### 🔧 Improvements & Suggestions

- **BE126-03 · minor · `domain/retention/dto/PurgeResultDto.java:28`** — Javadoc pola `errorMessage`: „komunikat błędu przy status=FAILED (null przy sukcesie)". Po BE-126 to nieprawdziwe: `RetentionPurgeServiceImpl:181-187` zapisuje niepusty `errorMessage` (ostrzeżenie o `s3Failures`) także dla `status=COMPLETED`, a `PurgeResultDto.from` (bez zmian w tym diffie) mapuje `entity.getErrorMessage()` bezwarunkowo — więc REST (`GET .../purge/{id}`, `.../history`, BE-118) już dziś może zwrócić `{"status":"COMPLETED","errorMessage":"S3 delete failures: 2 — ..."}`. To poprawne zachowanie API, ale sam opis kontraktu w Javadoc DTO nie został zaktualizowany, a to jedyne miejsce, gdzie konsument (przyszłe FE-110, per `TASKS-BACKEND.md` blokowane przez BE-126/127/128) dowie się o semantyce pola. Ryzyko: ktoś zaimplementuje FE zgodnie z Javadoc („errorMessage != null ⇒ czerwony banner FAILED") i pokaże mylący komunikat dla poprawnie zakończonego purge z ostrzeżeniem.
  Rekomendacja: jednolinijkowa poprawka Javadoc — „komunikat błędu (status=FAILED) lub ostrzeżenia (status=COMPLETED, np. częściowe porażki S3 — BE-126)". Tani fix, teraz póki kontekst jest świeży.

- **BE126-04 · nit · `TASKS-BACKEND.md:7227` i `:7212`** — notatka wykonawcy liczbowo przecenia zakres nowych testów: „rozszerzenie `RetentionPurgeServiceImplTest` o 12 nowych testów" — policzone `@Test` w pliku: 30 (przy `545c6e9`) → 38 (obecnie) = **+8**, nie 12; podobnie AC (WP-1) mówi o „13 testów" dla `ContactRepositoryPurgeCandidatesIntegrationTest`, plik ma **12** (`grep -c @Test`). Nie wpływa na jakość kodu/testów (które są solidne — patrz „Pozytywne obserwacje"), tylko na rzetelność zapisu dla przyszłych czytelników `TASKS-BACKEND.md`.

- **BE126-05 · informacyjne · `TASKS-BACKEND.md:7236,7244`** — uwaga kontraktowa BE125-04 w sekcji BE-126 („decyzja API … zapada w BE-143") nie została zaktualizowana po tym, jak BE-143 faktycznie podjęła tę decyzję (`EmailAttachmentKeys.isRecordingKeyOwnedByTenant`, publiczna, gotowa do użycia w BE-126 pkt (b) przy jego podjęciu). Sam wykonawca BE-143 zgłasza to jako świadomą lukę procesu (nie mógł edytować cudzej sekcji w tej iteracji). Rekomendacja: przy podejmowaniu BE-126 pkt (b) (purge `contact.recording_url`, dziś celowo odłożony) skopiować „Decyzję API" z sekcji BE-143 zamiast szukać jej od nowa.

### ✅ Positive Observations

- **Ścieżka legacy jest bajt-w-bajt identyczna** — porównałem ciało `purgeContactInteractionsLegacy` (`RetentionPurgeServiceImpl.java:223-244`) z `purgeContactInteractions` sprzed zmiany (`git show 545c6e9:...`) linia po linii: jedyna różnica to nazwa metody. Flaga `retention.purge.delete-messages` domyślna `false` w JEDYNYM miejscu (`application.yml:344`), bez nadpisania w `application-dev.yml`/`application-prod.yml`/`application-test.yml`/`.env.local-demo`/`docker-compose*.yml` (sprawdzone `grep` po wszystkich plikach `application*.yml`/`*.env*`/`*compose*` w repo) — dokładnie tak, jak wymagało zlecenie.
- **Strategia H-1 (keyset) jest poprawną, dobrze przetestowaną odpowiedzią na hipotezę z code review BE-125** — `ContactRepository#findContactIdsOlderThan` (`:625-694`) implementuje porządek `(started_at, contact_id)` z kursorem porównywanym jako krotka Postgresa (`(started_at, contact_id) > (:cursorStartedAt, :cursorContactId)`), co strukturalnie gwarantuje brak duplikatów i luk między stronami — potwierdzone testem `keysetPagination_noOverlapNoGaps` (25 kontaktów, 3 pełne+niepełna strona, `Set` sumy = dokładnie 25 elementów) i `cursorAdvancesPastCandidate_regardlessOfDeletion` (kandydat celowo NIE usunięty w teście nadal nie wraca na kolejnej stronie). Test na mockach `HeadOfLineBlockingDefense` (`RetentionPurgeServiceImplTest.java:962-1020`) jest szczególnie dobry: dowodzi, że 2 trwale zablokowane kontakty (= `batchSize`) na początku NIE zatrzymują usunięcia młodszego kontaktu na drugiej stronie — dokładnie scenariusz H-1 z code review BE-125.
- **Pułapka `UUID.compareTo()` vs porządek bajtowy `uuid` w Postgresie poprawnie rozpoznana i obejśnięta w teście** — `deterministicOrder_tieBreaksByContactId` (`ContactRepositoryPurgeCandidatesIntegrationTest.java:130-146`) buduje oczekiwaną kolejność przez `Comparator.comparing(UUID::toString)`, nie przez domyślny `Comparable<UUID>` — z komentarzem wyjaśniającym dlaczego. Bez tego test failowałby losowo mimo poprawnej implementacji SQL (dokładnie tak, jak opisuje pamięć agenta wykonawcy tego ticketu).
- **Drugi przebieg `purgeByContactIds` (naprawa BE125-02) jest poprawny, tani i dobrze przetestowany** — wywoływany WYŁĄCZNIE dla `actuallyDeletedContacts` (nie całej strony), więc koszt jest ograniczony do ≤ `batchSize` ID; kolejność weryfikowana przez `InOrder` w `singlePage_noBlocked_secondPassCatchesRaceWindowMessage` (email+social pierwszy przebieg → `deleteContacts` → email+social drugi przebieg); `BlockedContacts` dowodzi, że drugi przebieg NIE jest wołany dla w całości zablokowanej strony (uniknięcie zbędnego zapytania). Sumowanie liczników przez `PurgedMessages#plus` jest bezpieczne, bo oba przebiegi operują na rozłącznych zbiorach wiadomości (pierwszy usuwa to, co istniało w chwili SELECT-a; drugi może znaleźć tylko to, co dopisano PO nim).
- **Audyt**: `breakdown` zawiera wszystkie 7 pól z decyzji wykonawcy (w tym `s3Rejected`, pominięty w pierwotnym Zakresie ticketu) — zweryfikowane testem `AuditBreakdown` asercjami po wartościach, nie tylko obecności kluczy. Decyzja „`s3Failures > 0` → `COMPLETED` z `error_message`, nie `FAILED`" jest rozsądna i widoczna dla operatora (pole `error_message` jest teraz eksponowane przez `PurgeResultDto` niezależnie od statusu — patrz BE126-03 o nieaktualnym Javadoc tego pola, nie o samej decyzji, którą uważam za trafną: reszta batcha kończy się poprawnie i `FAILED` całej operacji byłby myzący).
- **`ContactServiceImpl`/`ContactService`** — czyste przekazanie (pass-through) bez dodatkowej logiki, Javadoc konsekwentnie odsyła do `ContactRepository` jako źródła prawdy o kontrakcie.

### Summary

**Ocena: 4/5 ⭐** — Rdzeń (bajt-w-bajt niezmieniona ścieżka legacy, flaga domyślnie `false` wszędzie, poprawna i dobrze przetestowana strategia H-1, poprawny i tani drugi przebieg BE125-02, kompletny audyt) jest solidny i bezpieczny do scalenia. Jedyne ustalenie o wadze major (BE126-01) jest **dziś nieosiągalne** (wymaga utraty `BYPASSRLS` przez rolę aplikacyjną — potwierdzone na żywej bazie, że dziś tak nie jest) i dotyczy trade-offu, który sam wykonawca świadomie wybrał dla dobrej przyczyny (naprawa H-1) — wymaga udokumentowania, nie cofnięcia decyzji. Pozostałe ustalenia to drobna, nieblokująca okazja do poprawy wydajności (BE126-02) i dwie nieścisłości dokumentacyjne (BE126-03/04/05). **Werdykt: zatwierdzić z poprawkami** (żadne ustalenie nie blokuje scalenia; BE126-03 warto zrobić od razu — jedna linijka Javadoc, zanim ktoś zbuduje FE na błędnym założeniu).

---

## Review: BE-143 — walidacja prefiksu tenanta dla `s3Key` w wysyłce e-mail i pobieraniu załącznika (EPIC-30) — 2026-09-22

**Branch:** `feature/epic-30-message-retention` (niezacommitowane względem HEAD `545c6e9`)
**Reviewer:** senior-code-reviewer agent
**Zakres:** `domain/email/{EmailAttachmentKeys, EmailSendServiceImpl, EmailSendService}`, nowy `domain/email/EmailAttachmentAccessDeniedException`, `api/GlobalExceptionHandler`, `api/email/EmailAttachmentController`.
**Zakres (test):** zmienione `domain/email/{EmailSendServiceTest, EmailAttachmentKeysTest}`, `api/GlobalExceptionHandlerTest`; nowe `api/email/{EmailAttachmentControllerTest, EmailControllerTest}`.
**Metoda:** czytanie kodu i testów względem `TASKS-BACKEND.md` (BE-143: Zakres a-d, notatka wykonawcy z decyzją API, AC, Ryzyka) i `DESIGN-message-retention-and-partitioning.md` (U18); weryfikacja schematu kluczy `isRecordingKeyOwnedByTenant` przeciw RZECZYWISTYM producentom (`RecordingServiceImpl#buildS3Key`, `EmailEmlService#buildEmlS3Key`) w kodzie, nie tylko w komentarzu. **Nie uruchamiałem Mavena**, nie pisałem do bazy/MinIO (ten ticket nie dotyka schematu DB).

### Indeks ustaleń (wg wagi)

| ID | Waga | Status | Miejsce | Streszczenie |
|----|------|--------|---------|--------------|
| BE143-01 | nit | potwierdzone, niskie ryzyko | `EmailReplyRequest.java` (DTO, poza zakresem plików BE-143), `EmailAttachmentControllerTest`/`EmailControllerTest` | AC „(WP-1) MockMvc" niespełnione dosłownie, ale realny ubytek pokrycia jest mały — `PendingAttachment.s3Key` nie ma i tak żadnej adnotacji Bean Validation, a `GlobalExceptionHandler` to already-tested `@RestControllerAdvice` |
| BE143-02 | — (informacyjne, duplikat BE126-05) | potwierdzone | `TASKS-BACKEND.md:7731` | AC „decyzja API przekazana do BE-126/BE-129/BE-131" świadomie pozostawione odhaczone jako niewykonane — poza zakresem plików edytowalnych w tej iteracji |

### 🐛 Bugs / Critical Issues

_Brak potwierdzonych bugów._

### ⚠️ Security Concerns

_Brak nowych luk — przeciwnie, ten ticket ZAMYKA lukę BE125-01 z poprzedniego review._ Zweryfikowałem każdy z czterech wymaganych warunków osobno w kodzie (nie tylko w komentarzu):

1. **Walidacja PRZED jakimkolwiek I/O** — `validateAttachmentKeys(tenantId, safeAttachments)` jest DOSŁOWNIE pierwszą instrukcją w `sendReply` (`EmailSendServiceImpl.java:48-52`, przed `emailMessageRepository.findById` na `:55`) i w `sendNew` (`:167-171`, przed `tenantService.findTenantEntity` na `:174`) — potwierdzone też przez testy `verifyNoInteractions(attachmentStorageService, emailMessageRepository, emailEventPublisher, tenantService)` w `EmailSendServiceTest$SendNewAttachmentKeyValidation`/`$SendReplyAttachmentKeyValidation` (linie ok. 268-395), czyli nie tylko inspekcja kodu, ale i dowód na poziomie testu jednostkowego z rzeczywistym `EmailSendServiceImpl`.
2. **Kompletność ścieżek** — jedyne dwa wejścia z `attachments` w API to `POST /messages/{id}/reply` → `sendReply` i `POST /messages/outbound` → `sendNew` (`EmailController.java:146-165,174-187`, zweryfikowane grepem po `PendingAttachment`/`@PostMapping` w całym kontrolerze). `sendReplyWithTemplate` (deklarowana w interfejsie, zaimplementowana, ale nieużywana przez żaden kontroler ani test — martwy kod sprzed BE-143, poza zakresem) deleguje do `sendReply(..., List.of())` (`EmailSendServiceImpl.java:154`) — pusta lista, walidacja to no-op, brak luki.
3. **`isRecordingKeyOwnedByTenant` — schemat poprawny względem RZECZYWISTYCH producentów** — `RecordingServiceImpl#buildS3Key` (`:329-333`) zwraca `"{tenantId}/{rok}/{miesiąc}/{contactId}.mp3"`, `EmailEmlService` (`:121`, `buildEmlS3Key`) zwraca `"{tenantId}/{rok}/{miesiąc}/{contactId}.eml"` — oba BEZ korzenia `email-attachments/`. `isRecordingKeyOwnedByTenant(tenantId, s3Key)` (`EmailAttachmentKeys.java:128-133`) sprawdza prefiks `tenantId + "/"` — dokładne dopasowanie, ani za szeroki (odrzuca `email-attachments/{tenantId}/...` — inny korzeń, potwierdzone testem `attachmentSchemeKey_rejected`), ani za wąski (akceptuje realne klucze nagrań/EML — potwierdzone `ownRecordingAndEmlKeys_allowed`). Metoda jest dziś nieużywana produkcyjnie (przygotowanie pod BE-126 pkt (b)/BE-129/BE-131, zgodnie z notatką), co jest poprawnie udokumentowane w Javadoc i nie jest martwym kodem bez przeznaczenia.
4. **Wiadomość wyjątku bez surowego `s3Key`** — `EmailAttachmentAccessDeniedException` ma JEDEN konstruktor przyjmujący gotowy `message`; jedyne miejsce tworzące ten wyjątek (`EmailSendServiceImpl.java:404-405`) przekazuje statyczny, sztywny string „Załącznik wskazuje na obiekt S3 spoza dozwolonego zakresu tenanta" — nigdzie w kodzie produkcyjnym `s3Key`/`forLog(s3Key)` nie trafia do konstruktora wyjątku, tylko do `log.warn` (`:400-403`, przez `EmailAttachmentKeys.forLog`). `GlobalExceptionHandler` (`:127`) ustawia `problem.setDetail(...)` na TEN SAM sztywny string (nie `ex.getMessage()`), więc nawet gdyby ktoś w przyszłości zmienił miejsce tworzenia wyjątku i wstrzyknął tam klucz, odpowiedź HTTP wciąż by go nie ujawniła — potwierdzone testem `emailAttachmentAccessDenied_responseDoesNotLeakRawKey` (`GlobalExceptionHandlerTest.java`), który CELOWO konstruuje wyjątek z surowym kluczem w wiadomości, żeby udowodnić, że handler i tak go nie przepisuje do odpowiedzi. `ex.getMessage()` trafia WYŁĄCZNIE do `log.warn` po stronie serwera (`GlobalExceptionHandler.java:135`) — zgodne z resztą repo (`forLog` wszędzie indziej).
   Kod 403 przez RFC 7807 `ProblemDetail`, wzorzec 1:1 z `handleCrossTenantAccessException` (typ URI, `setProperty("timestamp", ...)`, log na WARN) — spójne z istniejącą konwencją.
5. **Regresja** — własny klucz `pending/` (OUTBOUND) i klucz INBOUND `{messageId}/` nadal przechodzą: potwierdzone end-to-end na rzeczywistym `EmailSendServiceImpl` (`SendNewAttachmentKeyValidation#ownPendingKey_allowedAndPersisted`, `SendReplyAttachmentKeyValidation#ownInboundStyleKey_allowedAndPersisted` — obie asercje sprawdzają, że klucz faktycznie trafia do `EmailMessage.attachments` zapisanej encji, nie tylko że walidacja nie rzuca) oraz w `EmailAttachmentControllerTest` dla pobierania (`ownInboundKey_returns200WithPresignedUrl`, `ownPendingKey_returns200WithPresignedUrl`).

### 🏗️ Architecture / Pattern Violations

_Brak naruszeń reguł z `CLAUDE.md`._ Brak nowych endpointów publicznych (zmiana dotyczy istniejących, uwierzytelnionych endpointów). Jedna implementacja allow-listy (`EmailAttachmentKeys.isOwnedByTenant`) reużywana identycznie przez wysyłkę, pobieranie i purge (BE-125) — zweryfikowane grepem: wszystkie pięć miejsc wołających w kodzie produkcyjnym (`EmailMessageServiceImpl`, `EmailSendServiceImpl`, `EmailAttachmentController`) wołają dokładnie tę samą metodę statyczną, zero duplikatów/rozjazdów. DTO/encja: `EmailAttachmentAccessDeniedException` żyje w `domain.email` (warstwa domenowa), mapowanie HTTP w `api.GlobalExceptionHandler` — poprawny podział odpowiedzialności.

### 🔧 Improvements & Suggestions

- **BE143-01 · nit · `EmailAttachmentControllerTest`, `EmailControllerTest`, `EmailReplyRequest.java`** — AC (WP-1) wymagał dosłownie testu `MockMvc`/integracyjnego przez HTTP; wykonawca świadomie rozłożył pokrycie na 3 klasy (logika biznesowa na rzeczywistym `EmailSendServiceImpl`, przekazanie parametrów w kontrolerze na mocku serwisu, mapowanie na 403 w `GlobalExceptionHandlerTest`) — ten sam kompromis, co reszta pakietu `api.*` w tym repo (`RetentionControllerTest` i inne, żaden nie używa `MockMvc`). Realny ubytek pokrycia: (a) deserializacja JSON zagnieżdżonego rekordu `PendingAttachment` przez Jackson na prawdziwym `@RequestBody` — nietestowana, ale rekord jest prosty (4 pola, brak customowych (de)serializerów), ryzyko niskie; (b) `@Valid` na `EmailReplyRequest`/`OutboundEmailRequest` — sprawdziłem `PendingAttachment.s3Key` NIE ma żadnej adnotacji Bean Validation (`@NotBlank`/`@Pattern`), więc nawet pełny `MockMvc` nie złapałby nic dodatkowego dla TEGO konkretnego pola — cała ochrona i tak żyje w `validateAttachmentKeys`, która jest wyczerpująco przetestowana; (c) samo wiązanie `@RestControllerAdvice`→wyjątek na poziomie Springa (nie Javy) — generyczny mechanizm, już wielokrotnie dowiedziony dla siostrzanych wyjątków w tym samym pliku (`CrossTenantAccessException` i inne). Uznaję odstępstwo za akceptowalne przy obecnej konwencji repo, ale gdyby kiedyś dodano `@NotBlank`/`@Pattern` na `s3Key` (obrona w głąb na poziomie DTO), wtedy realna wartość jednego testu `MockMvc` by wzrosła i warto go dorobić.
- **BE143-02 · informacyjne · `TASKS-BACKEND.md:7731`** — patrz BE126-05 (ten sam fakt, opisany z drugiej strony przez wykonawcę BE-143). Nic do zrobienia w kodzie.

### ✅ Positive Observations

- **`EmailAttachmentKeys` jako jedyne źródło prawdy jest utrzymane konsekwentnie** — upublicznienie `isOwnedByTenant`/`forLog` (były package-private) zamiast kopiowania logiki `startsWith` do `EmailAttachmentController` i `EmailSendServiceImpl` eliminuje dokładnie ten rodzaj rozjazdu, który spowodował lukę BE125-01 (kontroler miał goły `startsWith` bez odrzutu `.`/`..`, serwis wysyłki nie miał NIC). Nowa `isRecordingKeyOwnedByTenant` dzieli prywatną `hasCleanPrefixedSuffix` z `isOwnedByTenant` — ta sama logika bezpieczeństwa (segmenty `.`/`..`, znaki sterujące), inny prefiks, zero duplikacji.
- **Decyzja o kodzie błędu (403, nie 400) jest trafna i dobrze uzasadniona** — spójna z semantyką „przekroczenie granicy tenanta" (jak `CrossTenantAccessException`) zamiast „błąd formatu danych". Nowa klasa wyjątku zamiast rozszerzenia `CrossTenantAccessException` jest rozsądnym kompromisem (uniknięcie zmiany sygnatury współdzielonego wyjątku dla `resourceId: UUID` vs `s3Key: String`).
- **Test `emailAttachmentAccessDenied_responseDoesNotLeakRawKey`** jest przykładem dobrej praktyki testowej — nie tylko sprawdza „typowy" przypadek (wyjątek bez klucza w wiadomości), ale ADWERSARYJNIE konstruuje wyjątek Z kluczem w wiadomości, żeby udowodnić, że handler i tak go nie przepisze. To silniejsza gwarancja niż zwykły happy-path test.
- **Weryfikacja FE (poza zakresem zmian, ale zrobiona przez wykonawcę)** — potwierdzenie grepem, że `pendingAttachments`/`s3Key` w `email-contact.component.ts`/`adhoc-email-modal.component.ts` pochodzą wyłącznie z odpowiedzi uploadu (`resp.s3Key`), bez pola formularza pozwalającego wpisać klucz ręcznie — należyta staranność wykraczająca poza formalny zakres ticketu (weryfikacja, że nowy 403 nie zepsuje istniejącego, poprawnego użycia z UI).
- **Testy `sendNew`/`sendReply` w `EmailSendServiceTest`** konsekwentnie sprawdzają NIE TYLKO wynik walidacji, ale i BRAK skutków ubocznych (`verifyNoInteractions` na S3/repo/tenant/event publisher) — mocna gwarancja „całe żądanie odrzucone przed jakimkolwiek I/O", zgodna z Zakresem ticketu.

### Summary

**Ocena: 5/5 ⭐** — Ticket domyka realną, wcześniej niezałataną lukę bezpieczeństwa (BE125-01, cross-tenant przez wysyłkę e-maila) w sposób staranny: jedna, reużywalna implementacja allow-listy, walidacja udowodniona jako pierwsza instrukcja (kodem i testem), wyjątek i handler bez wycieku surowego klucza (udowodnione adwersaryjnym testem), pełna regresja własnych kluczy INBOUND/`pending/` sprawdzona end-to-end na rzeczywistym serwisie, przygotowanie API (`isRecordingKeyOwnedByTenant`) zweryfikowane względem prawdziwych producentów kluczy, nie tylko względem specyfikacji. Jedyne ustalenia to nit-level odstępstwo od dosłownego brzmienia AC (MockMvc, niskie realne ryzyko — uzasadnione) i drobna luka procesowa w propagacji notatki do sekcji BE-126 (nie problem kodu). **Werdykt: zatwierdzić bez zastrzeżeń.**

---

## Review: BE-129 — Przepływ RODO w `GdprServiceImpl` (D3): anonimizacja i eksport z wiadomościami, callbackami i rekordami kampanii; sprzątanie S3 (EPIC-30) — 2026-09-24

**Branch:** `feature/epic-30-message-retention` (niezacommitowane względem HEAD `0e3fdf7`)
**Reviewer:** senior-code-reviewer agent
**Zakres:** nowe `domain/gdpr/GdprRepository.java`, `api/customer/dto/AnonymizePreviewResponse.java`, `domain/gdpr/GdprServiceIntegrationTest.java`; zmienione `domain/gdpr/{GdprService.java,GdprServiceImpl.java}`, `api/customer/{GdprController.java,CustomerController.java}`, `domain/customer/{CustomerService.java,CustomerServiceImpl.java,CustomerRepository.java}`, `domain/gdpr/GdprServiceTest.java`, `api/CustomerControllerTest.java`; `TASKS-BACKEND.md` (sekcja BE-129, tylko odczyt). **Poza zakresem** (zrecenzowane osobno, tylko konsumowane tutaj): V095/DB-061 i V096/DB-062 (patrz wpisy DB-061/DB-062 w `CR-DATABASE.md`).

**Metoda:** czytanie kodu linia-po-linii z prześledzeniem CAŁEGO łańcucha wywołań `dryRun`/`tenantId` od kontrolera do SQL (nie tylko punktowa inspekcja `GdprRepository`); niezależny grep całego repo (`backend`, `frontend`) po pozostałych wołających trzech `@Deprecated` metod; weryfikacja nazw kolumn/statusów (`campaign_contact.status='DIALING'`, `scheduled_callback.status='PROCESSING'`, `record_id`/`callback_id`) względem RZECZYWISTYCH producentów tych stanów (`ProgressiveDialerServiceImpl`, `ScheduledCallbackExecutor`/`ScheduledCallbackRepository`), nie tylko względem tekstu ticketu; czytanie `V096` linia-po-linii dla predykatu `campaign_contact` UPDATE, żeby ocenić realność okna wyścigu opisanego w zleceniu; weryfikacja istnienia/sygnatur `EmailAttachmentStorageService#presignedDownloadUrl`, `RecordingService#generatePresignedUrlForKey`, `S3Properties#getPresignedUrlExpirationMinutes`; grep całego drzewa testów po `GdprController`/`gdpr/anonymize` i po `ConflictException` w `GlobalExceptionHandlerTest`. **Nie uruchamiałem Mavena** (równoległy build zlecającego), **nie wywoływałem `anonymize_customer`/`export_customer_data` na żadnej bazie** (ani żywej, ani scratch) — ta recenzja jest czytaniem kodu, nie egzekucją; tam, gdzie to obniża pewność ustalenia, zaznaczone poniżej wprost.

### Indeks ustaleń (wg wagi)

| ID | Waga | Status | Miejsce | Streszczenie |
|----|------|--------|---------|--------------|
| BE129-01 | **major** | potwierdzone czytaniem kodu (locking primitives), NIE wykonaniem współbieżnym | `GdprRepository.java:66-82,121` vs `ProgressiveDialerServiceImpl.java:430` (`FOR UPDATE SKIP LOCKED`), `V096…sql:430-448` | Guard „rekordów w toku" robi zwykły `SELECT` bez blokady wiersza; okno TOCTOU między guardem a mutacją `anonymize_customer` w tej samej transakcji (READ COMMITTED) pozwala realnemu wyścigowi wyzerować PII w `campaign_contact` rekordu, który WŁAŚNIE stał się `DIALING` |
| BE129-02 | major | potwierdzone (grep całego repo testów) | brak `GdprControllerTest.java`; `GlobalExceptionHandlerTest.java` bez testu `ConflictException` | Zero testów HTTP/MockMvc dla trzech endpointów `GdprController` (w tym nowego `GET .../preview`) — `@PreAuthorize` i mapowanie 409 nigdy nie są dowiedzione na poziomie Springa, tylko na poziomie gołej propagacji wyjątku Java |
| BE129-03 | minor | potwierdzone czytaniem kodu | `GdprRepository.java:113,149` | `assertSameTenant(tenantId, customerId)` porównuje `TenantContext.getTenantId()` z wartością, która ZAWSZE pochodzi z tego samego `TenantContext.getTenantId()` u wołającego — tautologia, nigdy nie rzuci; prawdziwa izolacja tenanta żyje wyłącznie w predykatach `WHERE tenant_id = …` (już zweryfikowanych w DB-061/062) |
| BE129-04 | minor | potwierdzone (zgodne z AC, ale operacyjnie kruche) | `GdprServiceImpl.java:193-228` | Lista niepowodzeń usuwania S3 trafia wyłącznie do logu ERROR — spełnia literę AC („audytu/logu”), ale nic trwałego/przeszukiwalnego nie zostaje po zwrocie odpowiedzi HTTP |
| BE129-05 | nit | potwierdzone | `GdprServiceIntegrationTest.java:192-193` | Test guardu DB061-08 asercjuje wyłącznie `isInstanceOf(RuntimeException.class)` — złapałby też niepowiązany błąd (np. NPE), nie tylko rzeczywistą kolizję `record_id` |
| BE129-06 | nit | potwierdzone | `GdprServiceTest.java:44-46` | Javadoc odsyła do dwóch klas testów integracyjnych (`GdprServiceAnonymizeIntegrationTest`/`GdprServiceExportIntegrationTest`), które nie istnieją — dostarczono jedną `GdprServiceIntegrationTest` z zagnieżdżonymi klasami |
| BE129-07 | nit (informacyjne) | potwierdzone | `GdprRepository.java:66-82` | `fn_customer_subject_ids` liczony DWA razy na rzeczywistą anonimizację (raz w guardzie Javy, raz w `anonymize_customer`) — ta sama, już zaakceptowana w DB-061/062 klasa ryzyka dla bardzo dużych klientów |

### 🐛 Bugs / Critical Issues

- **BE129-01 (major) — `GdprRepository.java:66-82` (`SQL_HAS_IN_PROGRESS_RECORDS`) i `:121` (wywołanie w `anonymize`), w relacji do `ProgressiveDialerServiceImpl.java:406-430` i `V096__extend_anonymize_customer_gdpr_art17.sql:430-448`.**
  Zlecenie recenzji wprost pytało o realność okna wyścigu „guard przechodzi → rekord przechodzi w DIALING → `anonymize_customer` i tak anonimizuje klienta w trakcie połączenia". Prześledziłem to KODEM (nie testem współbieżności):
  - Guard i właściwe wywołanie `anonymize_customer` wykonują się w JEDNEJ transakcji (`GdprRepository#anonymize` jest `@Transactional`, wywoływana z NIE-transakcyjnego `GdprServiceImpl`) — to spełnia dosłowne brzmienie zlecenia „w tej samej transakcji", ale PostgreSQL w domyślnym READ COMMITTED daje każdemu poleceniu w transakcji świeży snapshot na start tego polecenia, więc „ta sama transakcja" NIE oznacza „ten sam snapshot" — commit obcej transakcji między guardem a mutacją JEST widoczny.
  - Guard (`SQL_HAS_IN_PROGRESS_RECORDS`) to zwykły `SELECT`, BEZ `FOR UPDATE` — nie blokuje żadnego wiersza `campaign_contact`/`scheduled_callback`.
  - Realny „zajmujący" dla `campaign_contact`: `ProgressiveDialerServiceImpl.java:406-430` — `SELECT … FOR UPDATE SKIP LOCKED`, potem `UPDATE … SET status = 'DIALING'` (linia 459). Realny „zajmujący" dla `scheduled_callback`: `ScheduledCallbackRepository#updateStatusIfPending` (`:436-450`) — zwykły `UPDATE … WHERE status = 'PENDING'` (atomowy compare-and-swap przez WHERE, bez jawnego `FOR UPDATE`).
  - `V096…sql:430-441` (`UPDATE campaign_contact`) zeruje `phone`/`first_name`/`last_name`/`email`/`custom_fields` BEZWARUNKOWO względem statusu — predykat `WHERE … cc.phone IS NOT NULL OR …` dopasowuje rekord niezależnie od tego, czy `status IN ('PENDING','NO_ANSWER','CALLBACK')`; `CASE` zmienia SAM STATUS tylko dla tych trzech wartości, więc rekord `DIALING` zachowuje status `DIALING`, ale traci telefon/imię/nazwisko/e-mail/custom_fields.
  - **Sekwencja wyścigu (potwierdzona składnią blokad obu stron, NIE wykonana empirycznie):** T0 guard SELECT widzi `campaign_contact` w `PENDING` (brak rekordów w toku) → T1 `ProgressiveDialerServiceImpl` w INNEJ transakcji claim'uje ten sam rekord (`FOR UPDATE SKIP LOCKED` + `UPDATE … DIALING`, commit) → T2 `anonymize_customer` (dalej w PIERWSZEJ transakcji) wykonuje swój `UPDATE campaign_contact`, którego WHERE widzi już `DIALING` (bo to nowe polecenie w READ COMMITTED) i zeruje PII tego rekordu, mimo że guard „przepuścił" operację właśnie dlatego, że w T0 rekord jeszcze nie był w toku.
  - **Ocena ryzyka:** okno jest wąskie (jeden round-trip JDBC w obrębie jednej transakcji), nie psuje samego połączenia (numer telefonu do wybrania telefonia ma już w pamięci/komendzie, nie z tego zapytania) i końcowym stanem PII jest i tak zero (cel anonimizacji) — ale dzieje się to dokładnie w momencie, przed którym guard miał chronić, co czyni z guardu iluzoryczną gwarancję pod obciążeniem (a nie: „nigdy nie zdarzy się przy rekordzie w toku”, jak sugeruje AC). Nie nazywam tego blockerem, bo nie ma dowodu wykonania i konsekwencją nie jest utrata danych podmiotu ani naruszenie izolacji tenanta — ale to realna luka w kontrakcie, którego istnienie było explicite celem tego zadania.
  - **Rekomendacja:** w `SQL_HAS_IN_PROGRESS_RECORDS` dodać `FOR UPDATE` na wierszach zbioru podmiotu w `campaign_contact`/`scheduled_callback` (bez `SKIP LOCKED` — chcemy zablokować, nie pominąć). To tworzy właściwe wzajemne wykluczenie z obiema stronami: `ProgressiveDialerServiceImpl`'s `FOR UPDATE SKIP LOCKED` grzecznie POMINIE zablokowany wiersz (dialer przejdzie do następnego kontaktu zamiast czekać) — czysta, nieblokująca ścieżka; `updateStatusIfPending` (zwykły `UPDATE`) ZACZEKA na commit naszej transakcji, a potem trafi na już zmieniony przez `anonymize_customer` stan (`CANCELLED`/zerowe PII), więc jego `WHERE status = 'PENDING'` zwróci 0 i bezpiecznie się wycofa. Przed wdrożeniem zalecam test współbieżności (dwa wątki/połączenia) potwierdzający brak deadlocka.

### ⚠️ Security Concerns

_Brak nowych luk izolacji tenantów ani wycieku danych między tenantami — wszystkie predykaty `tenant_id`/`assertSameTenant`/`setTenantContextInDb` obecne tam, gdzie oczekiwane (patrz `Architecture` niżej dla szczegółów)._ DB062-01 (blocker z poprzedniej recenzji: `p_dry_run = NULL` po cichu wykonywał rzeczywistą anonimizację) jest **naprawiony w samym V096** (`p_dry_run IS NULL → RAISE EXCEPTION`, linie 191-196) — potwierdzone czytaniem migracji. **Obrona w głębi po stronie Javy jest kompletna i poprawna:** cały łańcuch `GdprController` (brak parametru `dryRun` w ogóle — dwie osobne metody) → `GdprServiceImpl.anonymizeCustomer`/`previewAnonymizeCustomer` (literały `false`/`true` wprost w kodzie, `GdprServiceImpl.java:108,129`) → `GdprRepository.anonymize(..., boolean dryRun)` (prymityw, `GdprRepository.java:112`) → `.setParameter("dryRun", dryRun)` (autoboxing prymitywu, nigdy `null`) — na ŻADNYM etapie nie istnieje boxed `Boolean` ani pole DTO, które mogłoby przenieść `null` do tego parametru. Klasa błędu z DB062-01 NIE powróciła w warstwie Javy.

- **BE129-03 (minor) — `GdprRepository.java:113,149`.** `assertSameTenant(tenantId, customerId)` w `anonymize()` i `exportCustomerData()` — sygnatura bazowa to `assertSameTenant(UUID entityTenantId, UUID resourceId)`, porównująca `entityTenantId` z `TenantContext.getTenantId()`. Tu `tenantId` przekazywany jako `entityTenantId` to DOKŁADNIE ta sama wartość, którą `GdprServiceImpl` chwilę wcześniej pobrało z `TenantContext.getTenantId()` (`GdprServiceImpl.java:60,102,122`) i przekazało dalej — w obrębie jednego synchronicznego wątku/żądania (brak `@Async`/przeskoku wątku w tym łańcuchu) ta wartość nie może się zmienić między odczytem a sprawdzeniem. Efekt: wywołanie porównuje kontekst z samym sobą i NIGDY nie może rzucić `CrossTenantAccessException` w praktyce — to nie jest realna luka bezpieczeństwa (prawdziwa izolacja tenanta w tej ścieżce w 100% żyje w `WHERE tenant_id = p_tenant_id`/`AND tenant_id = CAST(:tenantId AS uuid)` wewnątrz `exists()`, `hasInProgressRecords()` i samych funkcji SQL — niezależnie zweryfikowanych jako poprawne w recenzjach DB-061/DB-062), ale jest to MYLĄCE: konwencja projektu (`assertSameTenant(entity.getTenantId())`) zakłada porównanie z NIEZALEŻNIE ustaloną wartością encji (np. odczytaną z bazy), nie z kopią tego samego parametru. Rekomendacja: usunąć to wywołanie w `GdprRepository` (nie chroni niczego, czego nie chronią już predykaty SQL) albo zastąpić komentarzem wyjaśniającym, że to świadomy invariant-check, nie faktyczny guard cross-tenant — żeby przyszły czytelnik nie nabrał fałszywej pewności.

### 🏗️ Architecture / Pattern Violations

_Brak naruszeń reguł `CLAUDE.md` poza BE129-03 (który jest problemem klarowności/intencji, nie architektury per se)._ Potwierdzone punkt po punkcie ze zlecenia recenzji:

- **Konsolidacja DELETE/POST jest RZECZYWISTA, nie pozorna** — diff `CustomerController.java` pokazuje, że `anonymizeCustomer` usunęło WŁASNE wywołanie `customerService.anonymizeCustomer(id, tenantId)` i zastąpiło je `gdprService.anonymizeCustomer(id)` — identyczne wywołanie jak w `GdprController.java:116`. Zero zduplikowanej logiki walidacji/audytu w którymkolwiek kontrolerze (potwierdzone czytaniem obu plików w całości).
- **Kontrakt HTTP `DELETE` zachowany** — nadal `ResponseEntity<Void>` + `noContent()` (204); frontend `customer.service.ts:84-86` (`deleteCustomer`) używa `Observable<void>` z `http.delete<void>`, obojętne na 204 vs treść — brak regresji kontraktu. Jedyna zmiana widoczna z zewnątrz to nowy status 409 (udokumentowany w Swagger obu kontrolerów i w „Uwagach dla FE-112").
- **Jeden wpis audytu potwierdzony na poziomie kodu (nie tylko notatki)** — grep całego `GdprServiceImpl.java`/`GdprRepository.java` nie zawiera ŻADNEGO wywołania `auditLogService.publishAuditEvent` dla ścieżki anonimizacji (tylko dla eksportu, `GdprServiceImpl.java:79-90`, dokładnie jeden `GDPR_EXPORT`). `CustomerServiceImpl.anonymizeCustomer` (deprecated) straciło `@Audited(action = "CUSTOMER_ANONYMIZED")` (diff, linia ok. 302-311) — jedyne pozostałe `@Audited` w `CustomerServiceImpl` to `CUSTOMER_CREATED`/`CUSTOMER_UPDATED`, niepowiązane z anonimizacją.
- **`@Deprecated`, nie usunięte, i RZECZYWIŚCIE martwe produkcyjnie** — grep całego `backend/` po `customerService.anonymizeCustomer(`, `customerService.anonymize(`, `customerRepository.anonymize(` poza plikami testowymi zwraca WYŁĄCZNIE dwa self-referencyjne wywołania wewnątrz samych deprecated metod (`CustomerServiceImpl.java:311,543`) — żaden kontroler, joba ani inny serwis ich nie woła. Decyzja „zostawić jako `@Deprecated`” zamiast usunięcia jest uzasadniona i, po weryfikacji, faktycznie bezpieczna (metoda robi WYŁĄCZNIE `UPDATE customer`, więc gdyby coś jeszcze z niej korzystało, ominęłoby nową logikę — ale nic nie korzysta).
- **Klasyfikacja kluczy S3 do dwóch allow-list jest strukturalnie rozłączna i wyczerpująca dla znanych producentów.** `EmailAttachmentKeys.isOwnedByTenant` wymaga literalnego korzenia `"email-attachments/"` przed `{tenantId}`; `isRecordingKeyOwnedByTenant` wymaga korzenia `{tenantId}/` (bez `email-attachments/`) — rozłączność jest gwarantowana strukturalnie, bo `tenantId` to zawsze poprawny UUID, nigdy literalny string `"email-attachments"`. Klucz niepasujący do ŻADNEJ listy (np. przyszły format social z innym schematem) NIE ginie po cichu: `GdprServiceImpl.java:203-209` loguje WARN i dolicza do `failed` — czyli sygnalizowane jako niedokończona realizacja Art. 17 wymagająca ręcznej interwencji, zgodnie z filozofią reszty ticketu (nie cichy wyciek).
- **Kolejność DB → S3 potwierdzona przez brak `@Transactional` gdziekolwiek w `GdprServiceImpl.java`** (zweryfikowane grepem całego pliku — zero wystąpień adnotacji) — jedyna transakcja w całym łańcuchu anonimizacji to `GdprRepository#anonymize` (`@Transactional` na tej jednej metodzie). Ponieważ `GdprRepository` jest osobnym beanem Springa wołanym z NIE-transakcyjnego `GdprServiceImpl`, proxy Springa commituje transakcję w momencie powrotu z `gdprRepository.anonymize(...)` — sprzątanie S3 w `anonymizeCustomer()` (`GdprServiceImpl.java:113-114`) wykonuje się DOPIERO po tym powrocie, czyli PO commit. Brak self-invocation, brak ryzyka „S3 przed commitem” z DESIGN R8.
- **Błąd S3 pojedynczego klucza nie przerywa pętli** — `cleanupS3Objects` (`GdprServiceImpl.java:193-228`) łapie `RuntimeException` per-klucz wewnątrz pętli `for`, dolicza do `failed`, kontynuuje — potwierdzone też testem `s3FailureAfterCommit_doesNotRollbackAnonymization_continuesRemainingKeys` na prawdziwej bazie (drugi klucz nadal usuwany po awarii pierwszego).
- **Nazewnictwo statusów/kolumn w guardzie zweryfikowane względem PRAWDZIWYCH producentów, nie tylko tekstu ticketu** — `campaign_contact.status = 'DIALING'` potwierdzone w `CampaignContactRepository.java:285,317` i `ProgressiveDialerServiceImpl.java:459`; `scheduled_callback.status = 'PROCESSING'` potwierdzone w `ScheduledCallbackExecutor.java:147`/`ScheduledCallbackRepository.java` (`updateStatusIfPending`); `entity_id` dla `CAMPAIGN_CONTACT`/`SCHEDULED_CALLBACK` w `fn_customer_subject_ids` potwierdzone jako `record_id`/`callback_id` (`V095…sql:261,263,373,397`) — dokładnie te same kolumny, których guard używa w JOIN. Zero rozjazdu.

### 🔧 Improvements & Suggestions

- **BE129-02 (major, jakość testów) — brak `GdprControllerTest.java`.** Grep całego drzewa testów (`backend/app/src/test`) po `GdprController`/`gdpr/anonymize` nie zwraca ŻADNEGO wyniku — ani przed, ani po tym diffie. Trzy endpointy (`POST /export`, `POST /anonymize`, nowy `GET /anonymize/preview`) nie mają żadnego testu na poziomie Springa (MockMvc/`@WithMockUser`), który dowiódłby, że: (a) `@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")` faktycznie blokuje np. rolę AGENT dla tych konkretnych endpointów (adnotacja jest AOP-owa — testy jednostkowe wołające metodę kontrolera bezpośrednio, jak `CustomerControllerTest`, całkowicie ją omijają); (b) `ConflictException` faktycznie mapuje się na HTTP 409 dla TEGO wołania — sam `GlobalExceptionHandlerTest.java` (plik NIE dotknięty tym diffem) też nie zawiera testu `ConflictException`/`handleConflictException` (grep zwraca zero trafień), więc mechanizm 409 jest dziś NIEPRZETESTOWANY nigdzie w repo na poziomie jednostkowym, mimo że BE-129 jest pierwszym realnym, klientowskim wywołaniem tej ścieżki dla nieodwracalnej operacji. Nowy test w `CustomerControllerTest` (`anonymizeCustomer_propagatesConflictExceptionWhenRecordInProgress`) dowodzi tylko, że wyjątek Java przechodzi przez gołą metodę kontrolera — nie że Spring faktycznie zwróci 409. Ten wzorzec (goły `@InjectMocks`, bez `MockMvc`) jest już dziś stosowany niekonsekwentnie w tym repo — część kontrolerów (`EmailAttachmentControllerTest`, `RetentionControllerTest`, `CustomerImportControllerTest` i inne, potwierdzone grepem po `@WithMockUser`/`MockMvc`) MA testy na poziomie Springa, `CustomerControllerTest`/`GdprController` nie mają wcale. Rekomendacja: dodać `GdprControllerTest` (MockMvc + `@WithMockUser(roles = "AGENT")` → 403 dla wszystkich trzech endpointów, `@WithMockUser(roles = "SUPERVISOR")` → happy path, oraz jeden test `ConflictException` → 409 z rzeczywistym ciałem `ProblemDetail`) przed szerokim wdrożeniem — to jedyny sposób, żeby dowieść AC „Format błędu 409… RFC 7807 ProblemDetail” zamiast zakładać go z czytania kodu `GlobalExceptionHandler`.
- **BE129-04 (minor) — `GdprServiceImpl.java:193-228`.** Lista `failedKeys` z `cleanupS3Objects` trafia WYŁĄCZNIE do `log.error` (dwa wpisy: per-klucz i zbiorczy) — nic nie zapisuje jej do `audit_log` ani żadnej przeszukiwalnej tabeli; po powrocie z `anonymizeCustomer()` zmienna `failedKeys` istnieje tylko do zbudowania jednej linijki `log.info` z licznikiem i ginie. To formalnie spełnia AC („lista niepowodzeń S3 do audytu/logu” — log jest explicite dopuszczony), ale dla operacji nieodwracalnej i dotyczącej PII poleganie wyłącznie na logu ERROR (bez alertu/dashboardu/tabeli remediacji) oznacza, że jedynym sposobem wykrycia osieroconych obiektów S3 jest ręczne przeszukiwanie logów. Rekomendacja (nieblokująca): rozważyć w przyszłym tickecie trwały zapis (np. metadane w `audit_log` albo dedykowana tabela `gdpr_s3_cleanup_failure`) zamiast wyłącznie logu.
- **BE129-05 (nit) — `GdprServiceIntegrationTest.java:184-197` (`dbErrorBeforeCommit_doesNotCallS3Delete_noChanges`).** Asercja `assertThatThrownBy(...).isInstanceOf(RuntimeException.class)` jest zbyt szeroka — złapałaby też np. przypadkowy `NullPointerException` opakowany przez JPA, nie tylko rzeczywistą kolizję guardu DB061-08. Rekomendacja: dodać `.hasMessageContaining("DB061-08")`, zgodnie z udokumentowanym (choć kruchym) kontraktem string-matchingu z recenzji DB-062 (DB062-04).
- **BE129-06 (nit) — `GdprServiceTest.java:44-46`.** Javadoc klasy wspomina `GdprServiceAnonymizeIntegrationTest`/`GdprServiceExportIntegrationTest` jako odrębne klasy — w dostarczonym diffie istnieje jedna `GdprServiceIntegrationTest` z zagnieżdżonymi `@Nested` klasami. Czysto kosmetyczne, ale warto poprawić przy najbliższej okazji, żeby nie mylić przyszłego czytelnika szukającego nieistniejącego pliku.
- **BE129-07 (nit, informacyjne) — `GdprRepository.java:66-82`.** `fn_customer_subject_ids` jest liczona dwukrotnie dla każdej RZECZYWISTEJ (nie-podglądowej) anonimizacji: raz w guardzie Javy (`hasInProgressRecords`), raz wewnątrz `anonymize_customer` (V096). Dla klienta z bardzo dużą historią interakcji to podwaja nietrywialny koszt obliczeniowy przy każdym rzeczywistym wywołaniu (podgląd tego nie robi — guard jest pomijany dla `dryRun = true`). Ta sama klasa ryzyka („duży klient, brak strumieniowania”) była już świadomie zaakceptowana w recenzjach DB-061/DB-062 — nie traktuję jako nowego problemu, tylko odnotowuję kontynuację w warstwie Javy.

### ✅ Positive Observations

- **Obrona w głębi przeciw klasie błędu DB062-01 jest wzorcowa** — cały łańcuch Java (kontroler → serwis → repozytorium → JDBC parameter) używa WYŁĄCZNIE literałów/prymitywu `boolean`, nigdy boxed `Boolean` ani pola DTO mogącego przenieść `null`; nawet gdyby SQL-owa naprawa DB062-01 kiedyś zniknęła (np. przez błędną przyszłą migrację), warstwa Javy strukturalnie nie może odtworzyć tego konkretnego wektora.
- **`GdprServiceIntegrationTest` niezależnie replikuje predykaty SQL konsumentów** (`ProgressiveDialerServiceImpl#fetchNextPendingContact`, `ScheduledCallbackRepository`) w teście `afterAnonymize_dialerAndCallbackPredicates_returnZeroRows` zamiast ufać, że skoro `anonymize_customer` przechodzi swoje własne testy, to konsumenci też będą bezpieczni — to niezależna, silniejsza gwarancja kontraktu międzywarstwowego, rzadko spotykana praktyka.
- **Guard „rekordów w toku” reużywa `fn_customer_subject_ids` zamiast własnej reguły dopasowania** — dokładnie ta sama reguła mostu (`campaign_contact_record_id`) i te same dwie ścieżki dopasowania (link/identifier) co reszta D9 = A, więc guard i mutacja strukturalnie nie mogą się rozjechać co do TEGO, KOGO dotyczą (rozjazd możliwy jest tylko w czasie — patrz BE129-01).
- **Testy integracyjne (13) pokrywają dokładnie te scenariusze z AC, które wymagają prawdziwej bazy** — dosanityzowanie klienta ze starej ścieżki Javy, oba statusy guardu (DIALING/PROCESSING) z osobnymi testami, izolacja cross-tenant z dowodem braku zmian, brak ucięcia na 1050 kontaktach, manifest z obu schematów kluczy bez plików w ZIP, dokładnie jeden wpis audytowy dla obu operacji — żaden z tych testów nie jest tautologiczny (każdy asercjuje wartość, nie tylko brak wyjątku).
- **Dokumentacja (Javadoc `GdprService`/`GdprRepository`/`AnonymizePreviewResponse`) jest wyjątkowo precyzyjna** co do TEGO, co jest, a co nie jest gwarantowane (np. jawne wyjaśnienie, że dosanityzowanie klienta `is_deleted = TRUE` NIE rzuca, że guard dotyczy wyłącznie `dryRun = false`) — ułatwia recenzję i przyszłe utrzymanie znacznie bardziej niż przeciętny kod w tym repo.

### Summary

**Ocena: 3/5 ⭐ — zatwierdzić z poprawkami (nie blokować scalenia, ale domknąć BE129-01 i BE129-02 w krótkim odstępie).** Blocker z poprzedniej recenzji (DB062-01) jest naprawiony na poziomie SQL i dodatkowo zabezpieczony w Javie wzorcowo — najpoważniejsze ryzyko tego zadania jest realnie zamknięte. Konsolidacja dwóch ścieżek REST jest prawdziwa (nie kosmetyczna), jeden wpis audytu na operację jest potwierdzony kodem, kolejność DB→S3 jest poprawna i dowiedziona brakiem `@Transactional` w serwisie, klasyfikacja kluczy S3 jest rozłączna i nie gubi po cichu nieznanych kluczy, `@Deprecated` zamiast usunięcia jest bezpieczne (zero pozostałych wołających). Ocena nie jest wyższa z dwóch powodów: (1) **BE129-01** — guard „rekordów w toku”, czyli funkcja, którą ten ticket explicite dodał, żeby chronić aktywne połączenia przed anonimizacją w locie, ma udowodnione czytaniem kodu (blokad, nie wykonaniem) okno wyścigu, które w praktyce potrafi pozwolić dokładnie na to, czemu miał zapobiegać — wąskie, ale realne i tanie do naprawienia (`FOR UPDATE` w guardzie); (2) **BE129-02** — zero testów na poziomie Springa dla trzech endpointów REST tego ticketu (w tym nowego, wcześniej nieistniejącego `GET .../preview`) oznacza, że `@PreAuthorize` i mapowanie 409 są dziś wyłącznie założeniem, nie dowiedzionym faktem, dla operacji, którą sam ticket nazywa nieodwracalną i krytyczną. Żadne z pozostałych ustaleń (BE129-03…07) nie jest samodzielnym powodem blokady — to nity/minory do domknięcia przy okazji.

---

## Review: BE-127 — Purge wiadomości osieroconych (`contact_id IS NULL`) wg retencji tenanta + dry-run (EPIC-30) — 2026-09-26

**Branch:** `feature/epic-30-message-retention` (niezacommitowane względem HEAD `d6fd069`)
**Reviewer:** senior-code-reviewer agent
**Zakres (main):** nowe `domain/email/{EmailOrphanCursor, OrphanEmailPurgeBatch}`, `domain/social/{SocialOrphanCursor, OrphanSocialPurgeBatch}`; zmienione `domain/email/{EmailMessageRepository, EmailMessageService, EmailMessageServiceImpl}`, `domain/social/{SocialMessageRepository, SocialMessageService, SocialMessageServiceImpl}`, `domain/retention/RetentionPurgeServiceImpl`.
**Zakres (test):** nowe `domain/email/EmailMessageOrphanPurgeIntegrationTest` (13 testów), `domain/social/SocialMessageOrphanPurgeIntegrationTest` (12 testów); zmieniony `domain/retention/RetentionPurgeServiceImplTest` (+3 w nowym `@Nested OrphanSweep`, 2 istniejące testy rozszerzone).
**Metoda:** czytanie kodu i testów względem `TASKS-BACKEND.md` (BE-127, „Korekta z BE-124", „Uwaga z BE-125/BE-126/BE-143", notatka wykonawcy 2026-09-26) i `TASKS-DATABASE.md` (DB-059/V097); porównanie literalne `EmailMessageRepository.ORPHAN_AGE_EXPR` z treścią RZECZYWISTEJ, zastosowanej migracji `V097__add_orphan_message_age_purge_indexes.sql` (nie z pamięci/notatki); weryfikacja end-to-end WSZYSTKICH ścieżek zapisu `social_message` (`SocialWebhookController` → `SocialMessagePublisher` → RabbitMQ → `SocialMessageConsumer` → `SocialMessageServiceImpl#processIncomingMessage`/`#saveOutboundMessage`) dla twierdzenia „contact_id zawsze ustawiany synchronicznie"; grep całego `backend/app/src/main/java` (nie tylko literalnego `DELETE FROM contact`) po WSZYSTKICH mechanizmach usuwających wiersze `contact` (DELETE **i DDL**) dla weryfikacji decyzji „wariant dangling pominięty"; `\d contact` na żywej bazie tylko-odczyt (`PGOPTIONS='-c default_transaction_read_only=on'`) dla polityk RLS/FK. **Nie uruchamiałem Mavena** (równoległy build zlecającego), nie pisałem do żadnej bazy (żywej, demo ani scratch) — recenzja jest czytaniem kodu/migracji/notatek testowych, nie egzekucją.

### Indeks ustaleń (wg wagi)

| ID | Waga | Status | Miejsce | Streszczenie |
|----|------|--------|---------|--------------|
| BE127-01 | **major** (latentne, pre-existing, nie wprowadzone przez ten diff) | potwierdzone w kodzie (czytaniem), wyzwalacz — hipoteza | `domain/retention/PartitionReclaimJob.java:151-176,184-194` | decyzja „wariant dangling pominięty" opiera się na twierdzeniu „`contact` jest usuwany WYŁĄCZNIE przez `deleteContacts`/`deleteBatchOlderThan`" — pomija TRZECI mechanizm (`DROP TABLE` partycji), który usuwa wiersze `contact` bez żadnego sprzątania `email_message`/`social_message` |
| BE127-02 | — (potwierdzone poprawne, nie ustalenie) | potwierdzone (porównanie z plikiem migracji) | `EmailMessageRepository.java:298`, `V097…sql:70-71` | `ORPHAN_AGE_EXPR` jest bajt-w-bajt identyczne z wyrażeniem indeksu częściowego z DB-059/V097 — AC „literalna zgodność" spełnione |
| BE127-03 | — (potwierdzone poprawne, nie ustalenie) | potwierdzone (grep + lektura RabbitListener) | `SocialMessageServiceImpl.java:104-118,219-233`, `SocialMessageConsumer.java` | twierdzenie „`contact_id` zawsze ustawiany synchronicznie przed zapisem social" jest PRAWDZIWE dla WSZYSTKICH ścieżek zapisu (2 wywołania `save()` w całym repo, obie z `contactId` ustawionym przed wywołaniem) |

### 🐛 Bugs / Critical Issues

_Brak błędów logicznych w samym diffie BE-127._ Implementacja jest solidna — patrz „Positive Observations". Jedno ustalenie o wadze major dotyczy nie kodu tego diffu, a NIEKOMPLETNEJ weryfikacji stojącej za decyzją projektową podjętą w jego notatce wykonania:

- **BE127-01 · major (latentne) · `domain/retention/PartitionReclaimJob.java:151-176` (`reclaimTable`), `:184-194` (`warnIfStillHasRows`)** — Notatka wykonania BE-127 (`TASKS-BACKEND.md`, „Decyzja: wariant dangling — POMINIĘTY") uzasadnia pominięcie sweepu `NOT EXISTS` twierdzeniem: „wiersze `contact` są usuwane WYŁĄCZNIE przez `ContactRepository#deleteContacts` (BE-126) i `#deleteBatchOlderThan` (legacy) — zero innych `DELETE FROM contact` w Javie, zero w funkcjach SQL". Zweryfikowałem to twierdzenie DOSŁOWNIE (grep `DELETE FROM contact\b` w `backend/app/src/main/java` i we WSZYSTKICH migracjach) — jest prawdziwe w SWOJEJ LITERALNEJ postaci: nie istnieje żaden trzeci `DELETE FROM contact`. Ale grep szukał tylko `DELETE`, nie DDL — a `PartitionReclaimJob` (istniejący od BE-115, EPIC-29, NIE część tego diffu) usuwa wiersze `contact` przez **`DROP TABLE` całej partycji miesięcznej** (`PartitionScanner#dropPartition`, wywołane z `reclaimTable:169`), gdy `partition.rangeEnd()` jest starsze niż globalny próg liczony po NAJDŁUŻSZEJ retencji ze WSZYSTKICH tenantów (`retentionPolicyService.findMaxRetentionMonths`). Kluczowe: `warnIfStillHasRows` (`:184-194`) explicite sprawdza, czy partycja-kandydat do DROP wciąż ma wiersze, loguje WARN gdy tak — **i mimo to kontynuuje DROP** (komentarz w kodzie: „kontynuuję DROP mimo to"). Ten job:
  1. NIE woła `EmailMessageService`/`SocialMessageService` (żadnego purge wiadomości) przed/po DROP — sprawdzone: zero importów/wywołań domeny email/social w całym pliku.
  2. Usuwa wiersze `contact` niezależnie od tego, czy powiązane `email_message`/`social_message` (`contact_id` wskazujący na usunięty wiersz) istnieją — `email_message`/`social_message` NIE są dziś partycjonowane (DB-065/DB-067 wciąż otwarte) i nie mają FK do `contact` (potwierdzone `\d contact` na żywej bazie — brak jakiejkolwiek referencji przychodzącej udokumentowanej w schemacie; sam `contact` ma tylko polityki RLS `SELECT`/`INSERT`, bez `DELETE`, co jest zgodne z resztą recenzji BE-126), więc DROP nie kaskaduje i nie blokuje się na niczym — po prostu fizycznie usuwa wiersze `contact`, zostawiając wskazujące na nie `email_message.contact_id`/`social_message.contact_id` jako dangling.

  Scenariusz utraty/luki danych: gdyby purge Poziomu 1 (retencja per-tenant, ten sam serwis modyfikowany przez BE-126/BE-127) przestał działać dla jednego tenanta na czas dłuższy niż globalny bufor (`maxRetentionMonths` ze WSZYSTKICH tenantów — dziś demo ma tenantów z 6 i 60 miesiącami, więc bufor = 60 miesięcy; w praktyce wymaga wieloletniej, nieprzerwanej awarii/wyłączenia jobu retencji dla konkretnego tenanta) — `PartitionReclaimJob` i tak w końcu DROPnie starą partycję `contact` z pozostałymi wierszami (WARN, bez blokady). Każda wiadomość email/social wskazująca na kontakt w tej partycji staje się DOKŁADNIE tym „dangling", które BE-127 świadomie zdecydowało się nie sprzątać (sweep sierot łapie tylko `contact_id IS NULL`, nigdy `contact_id` wskazujący na nieistniejący wiersz) — i od tego momentu NIE ISTNIEJE żadna ścieżka usunięcia tej wiadomości w całym systemie (nie sweep sierot BE-127 — kryterium `IS NULL` jej nie złapie; nie purge kontaktu BE-126 — kontakt już nie istnieje).

  Ocena wagi: to NIE jest regresja wprowadzona przez BE-127 (job istnieje od BE-115, niezmieniony w tym diffie) i dziś zdarzenie wyzwalające jest odległe (wymaga wieloletniej awarii purge dla tenanta z krótszą retencją niż globalne maksimum) — stąd „major, latentne", nie blocker. Ale samo TWIERDZENIE w notatce wykonania („zero innych ścieżek usuwania `contact`") jest niekompletne, a decyzja „dangling nie jest potrzebny" była podjęta na jego podstawie. W odróżnieniu od BE126-01 (poprzednia recenzja, gdzie latentne ryzyko dotyczyło TEJ SAMEJ funkcji, którą recenzowano) — tu ryzyko dotyczy funkcji w INNYM, niezmienianym pliku, którą wykonawca BE-127 powinien był, ale nie zgrepował poprawną metodą (szukanie tylko `DELETE`, nie DDL/`DROP`).

  Rekomendacja (nieblokująca scalenia, do udokumentowania i ewentualnego follow-upu): (a) dopisać do notatki wykonania BE-127 i/lub do Javadoc decyzji „dangling pominięty" jawne zdanie o `PartitionReclaimJob` jako trzeciej, nieobsłużonej ścieżce — żeby przyszły czytelnik (BE-128/BE-130/BE-131, albo kolejna recenzja DB-065/DB-067) nie powtórzył tego samego niekompletnego wniosku; (b) rozważyć w `PartitionReclaimJob#warnIfStillHasRows` PRZED DROP jednorazowe wywołanie `EmailMessageService#purgeByContactIds`/`SocialMessageService#purgeByContactIds` dla `contact_id` z partycji-kandydata WCIĄŻ mającej wiersze (tania poprawka — dotyczy tylko ścieżki WARN, która z definicji ma być rzadka) ALBO dodać wariant `NOT EXISTS` do sweepu BE-127 jako drugą linię obrony niezależną od tego, JAK `contact` zniknął. Właścicielowi warto też potwierdzić, czy globalny bufor (max retencji ze wszystkich tenantów) faktycznie NIGDY nie może być krótszy niż czas naprawy awarii purge dla pojedynczego tenanta — dziś to założenie działa (60-miesięczny bufor), ale nie jest nigdzie explicite udokumentowane jako invariant, którego złamanie ma ten konkretny skutek.

### ⚠️ Security Concerns

_Brak nowych ustaleń bezpieczeństwa._ Multi-tenancy zachowana identycznie do BE-125/BE-126: `assertSameTenant(tenantId)` PRZED `setTenantContextInDb(tenantId)` w każdej z sześciu nowych metod repozytorium (`EmailMessageRepository#{countOrphansOlderThan,findOrphansOlderThan}`, `SocialMessageRepository#{countOrphansOlderThan,findOrphansOlderThan,deleteOrphansByIds}`), filtr `tenant_id = CAST(:tenantId AS uuid)` obecny w każdym z sześciu nowych SQL jako bariera niezależna od RLS. Testy izolacji tenantów (`mixture_onlyOldOrphanOfTenantAIsPurged` w obu klasach integracyjnych) dowodzą, że stara sierota tenanta B NIE jest usuwana przy sweepie tenanta A. `EmailAttachmentKeys.isOwnedByTenant` (allow-lista S3) jest reużyta przez `purgeRows` bez zmian dla ścieżki sierot — sierota z kluczem spoza prefiksu tenanta jest odrzucana identycznie jak przy purge kontaktu (BE125-01 już zamknięte w BE-143, nie dotyczy tej ścieżki inaczej).

### 🏗️ Architecture / Pattern Violations

_Brak naruszeń reguł z `CLAUDE.md`._ Obie zmienione klasy repozytorium rozszerzają `TenantAwareRepository`; brak nowych publicznych endpointów (zmiana wyłącznie w warstwie domain, `RetentionController`/`SecurityConfig`/`TenantFilter` nietknięte, sprawdzone `git status`). Reguła kolejności filtrów nie ma tu zastosowania (brak zmian HTTP).

- **WP-2 (TenantContext) — potwierdzone poprawne.** `EmailMessageRepository`/`SocialMessageRepository` nie wywołują `TenantContext.clear()`/`restore()` w żadnej z nowych metod (sprawdzone grepem obu plików) — kontrakt „wołający zarządza kontekstem" jest zachowany identycznie do BE-125/BE-126. `RetentionPurgeServiceImpl#purgeAsync` (`:146,201`) — `TenantContext.restore(snapshot)`/`clear()` w `finally` — NIE zmienione w tym diffie (`git diff` pokazuje te linie jako kontekst, nie jako zmianę), a dwie nowe pętle sweepu sierot są wywoływane WEWNĄTRZ `purgeAsync`, więc dziedziczą ten sam, już poprawny cykl życia kontekstu. Testy `TenantContextHandling` w obu nowych klasach integracyjnych dowodzą tego samego na granicy serwis→repozytorium (pusty kontekst → `IllegalStateException`, sukces → kontekst PRZETRWA wywołanie, bo metoda nigdy go nie czyści).
- **Flaga bezpiecznika — potwierdzone brak trzeciej flagi.** `grep -n "@Value" RetentionPurgeServiceImpl.java` zwraca wyłącznie `batch-size` (`:84`) i `delete-messages` (`:96`) — obie pętle sierot (`:391-412`) są WEWNĄTRZ `purgeContactInteractionsWithMessageDeletion`, czyli podpięte pod TĘ SAMĄ `deleteMessagesEnabled`, bez odrębnego przełącznika. Ścieżka legacy (`purgeContactInteractionsLegacy`, `:227-248`) jest w diffie WYŁĄCZNIE kontekstem (zero zmienionych linii — `git diff` na cały plik pokazuje jedynie insercje, 0 delecji poza jedną niezwiązaną linią komentarza) — potwierdzone też testem `flagFalse_neverCallsNewMethods`, rozszerzonym o dwie NOWE asercje `never()` dla `purgeOrphansOlderThan` (email i social) bez usunięcia żadnej z oryginalnych asercji.
- **Liczniki audytu — potwierdzone brak rozjazdu.** `orphanEmailMessagesDeleted`/`orphanSocialMessagesDeleted` wliczone w `ContactInteractionsPurgeResult#totalRowsDeleted()` (`:438-441`) ORAZ w `buildBreakdownJson` (`:647-655`) jako pola `orphanEmailMessages`/`orphanSocialMessages` OSOBNE od `emailMessages`/`socialMessages` — zweryfikowane testem `orphanSweep_runsAfterContactLoop_countsIncludedInRowsDeletedAndBreakdown` (asercja na WARTOŚCIACH `rowsDeleted=7` i obu polach breakdown, nie tylko na obecności kluczy). Liczniki S3 (`s3ObjectsDeleted`/`s3Failures`/`s3Rejected`) są sumowane WSPÓLNIE z fazą kontakt-tied w tych samych lokalnych akumulatorach (`:305-307`, `+=` w obu fazach) — decyzja udokumentowana w Javadoc, konsekwentna z BE-126.

### 🔧 Improvements & Suggestions

_Brak nowych ustaleń poza BE127-01 (już opisanym w „Bugs")._ Drobna, niesamodzielna obserwacja: `EmailMessageRepository#toInstant`/`SocialMessageRepository#toOrphanInstant` duplikują identyczny kod konwersji `java.sql.Timestamp → Instant`, który już istnieje jako `ContactRepository#toPurgeInstant` (BE-126) — zgodne z istniejącym, już zaakceptowanym w recenzji BE-125 wzorcem tego repo (duplikacja `IN_LIST_CHUNK_SIZE` między `EmailMessageRepository`/`SocialMessageRepository` z tego samego uzasadnienia: pakiety domenowe nie importują się wzajemnie bez potrzeby) — nie podnoszę jako osobne ustalenie.

### ✅ Positive Observations

- **Wyrażenie „wieku wiadomości" zweryfikowane bajt-w-bajt względem RZECZYWISTEJ migracji, nie względem notatki.** `EmailMessageRepository.ORPHAN_AGE_EXPR = "COALESCE(received_at, sent_at, created_at)"` (`:298`) jest identyczne znak w znak z wyrażeniem w `V097__add_orphan_message_age_purge_indexes.sql:70-71` (`ON email_message (tenant_id, (COALESCE(received_at, sent_at, created_at))) WHERE contact_id IS NULL`) — zero rozjazdu kolejności argumentów/nawiasów, który zepsułby dopasowanie predykatu indeksu. `.formatted(ORPHAN_AGE_EXPR)` gwarantuje to MECHANICZNIE dla wszystkich trzech zapytań (`COUNT_ORPHANS_SQL`/`FIND_ORPHANS_FIRST_PAGE_SQL`/`FIND_ORPHANS_NEXT_PAGE_SQL`), nie tylko konwencją nazewniczą — silniejsza gwarancja niż zwykłe „skopiuj-wklej" w trzech miejscach. Potwierdzone też empirycznie: `QueryPlans#explain_usesOrphanIndex`/`underAppUserRole_stillUsesIndex` w obu nowych klasach testowych dowodzą Index/Bitmap Scan bez Seq Scan dla WSZYSTKICH trzech kształtów zapytań, także pod restrykcyjną rolą (`app_user`-podobną, bez BYPASSRLS) z GUC tenanta.
- **Twierdzenie „social nie potrzebuje filtra resztkowego" jest zweryfikowane, nie założone.** Sprawdziłem WSZYSTKIE ścieżki zapisu `SocialMessage` w repo (dwa wywołania `socialMessageRepository.save()` w całym `backend/app/src/main/java` — `processIncomingWithTenantContext:118` i `saveOutboundMessage:233`) oraz cały łańcuch przychodzący (`SocialWebhookController` → `SocialMessagePublisher` → RabbitMQ → `SocialMessageConsumer#onSocialMessage` → `SocialMessageService#processIncomingMessage`) — w KAŻDYM przypadku `contactId` jest ustawiony (nowy kontakt utworzony synchronicznie albo istniejący dobrany) PRZED wywołaniem `save()`, w tej samej metodzie, bez żadnego okna asynchronicznego. Test `noResidualFilter_freshlyInsertedOldSentAt_isStillPurged` w `SocialMessageOrphanPurgeIntegrationTest` explicite dowodzi ODWROTNEGO zachowania niż e-mail (świeżo zapisana, ale „stara" wg `sent_at` sierota JEST usuwana) — dokładnie tak, jak wymaga uzasadnienie asymetrii, nie tylko deklaruje je w komentarzu.
- **Reużycie `purgeRows` bez duplikacji i bez założenia `contactId != null`.** `EmailMessageServiceImpl#purgeOrphansOlderThan` mapuje `OrphanCandidate → AttachmentsRow` (z `contactId = null` z definicji) i przekazuje do TEGO SAMEGO `purgeRows`, które budowałoby `purgeByContactIds` (BE-125) — zero duplikacji logiki S3/allow-lista/`DELETE…RETURNING`. Sprawdzone punkt po punkcie w `purgeRows` (`:116-146`): obie linie, które dodają do `blockedContacts`, są jawnie strzeżone `row.contactId() != null` — sierota nigdy nie trafia do zbioru blokad, zero ryzyka NPE z `Set.copyOf`/`contains(null)` (BE125-09, już zamknięte).
- **Testy integracyjne (13 email + 12 social) są wzorowe — asercje na wartościach, nie na „nie rzuciło wyjątku".** Pokrycie AC potwierdzone czytaniem, nie notatką: mieszanka stara-sierota/świeża-sierota-w-oknie-resztkowym(tylko email, z jawnym testem odwrotnym dla social)/powiązana/sierota-tenanta-B, granica cutoff (`<` wykluczające, nie `≤`), dry-run liczący DOKŁADNIE te same kryteria co purge, idempotencja, awaria S3 + retry, awaria S3 TRWAŁA ze stronicowaniem (dowód braku nieskończonej pętli — `iterations <= 10` + asercja `== 3`), stronicowanie keyset bez nakładania/dziur (25 wierszy, 3 strony, `Set` sumy = 25), `TenantContext` (throw + przetrwanie), `EXPLAIN` x2 index (bez Seq Scan) + pod rolą `app_user` z GUC, brak `ctid`. Social świadomie i poprawnie POMIJA test awarii S3 (Javadoc klasy: „Bez S3 — domena social nie ma obiektów w S3").
- **`RetentionPurgeServiceImplTest` — jedyna usunięta linia w całym diffie testowym (`verifyNoInteractions(emailMessageService, socialMessageService)`) zastąpiona SILNIEJSZĄ, nie słabszą asercją.** Ponieważ sweep sierot jest teraz bezwarunkowy, `verifyNoInteractions` przestałoby być prawdziwe (metody SĄ wołane) — wykonawca poprawnie zastąpił ją dwiema precyzyjnymi asercjami `never()` na `purgeByContactIds` (dokładnie to, co oryginalny test miał dowodzić) PLUS nowymi pozytywnymi asercjami `verify(...).purgeOrphansOlderThan(...)` (dowód, że sweep FAKTYCZNIE się odbył z oczekiwanymi argumentami) — potwierdzone `git diff`: to jedyna usunięta linia w całym pliku testowym, zero innych osłabień. `flagFalse_neverCallsNewMethods` analogicznie dostał TYLKO dwie nowe asercje `never()`, żadna oryginalna nie zniknęła.
- **Dokumentacja decyzji jest wyjątkowo transparentna.** Notatka wykonania BE-127 opisuje uzasadnienie asymetrii filtra resztkowego, decyzję o pominięciu wariantu dangling (z metodologią weryfikacji) i kształt breakdownu audytu na poziomie, który umożliwił tej recenzji szybkie zlokalizowanie JEDYNEJ dziury w tej metodologii (BE127-01) bez odgadywania intencji.

### Summary

**Ocena: 4/5 ⭐ — zatwierdzić z poprawkami (nie blokować scalenia).** Implementacja BE-127 jest dokładnym, dobrze przetestowanym powtórzeniem sprawdzonych wzorców z BE-125/BE-126 (S3-przed-wierszem, stronicowanie keyset H-1, allow-lista tenanta, potwierdzenie `DELETE…RETURNING`) zastosowanych do nowego kryterium (`contact_id IS NULL` wg wieku) — literalna zgodność wyrażenia „wieku" z indeksem DB-059/V097 jest udowodniona porównaniem z treścią migracji, nie z notatką; asymetria filtra resztkowego (tylko e-mail) jest zweryfikowana względem WSZYSTKICH ścieżek zapisu social, nie założona; testy są wartościowe i kompletne względem AC. Jedyne ustalenie (BE127-01, major) dotyczy NIE kodu tego diffu, a niekompletnej weryfikacji stojącej za decyzją „wariant dangling niepotrzebny" — `PartitionReclaimJob` (pre-existing, EPIC-29) jest trzecim, nieuwzględnionym mechanizmem usuwania wierszy `contact`, który w razie wieloletniej awarii purge dla pojedynczego tenanta mógłby wygenerować dangling wiadomości bez ŻADNEJ ścieżki sprzątania. To nie jest regresja wprowadzona przez ten diff i nie wymaga natychmiastowej naprawy kodu — wymaga udokumentowania w notatce decyzji i rozważenia jako follow-up (podobnie jak BE126-01 w poprzedniej recenzji), żeby przyszli czytelnicy nie powtórzyli tego samego niekompletnego wniosku.
