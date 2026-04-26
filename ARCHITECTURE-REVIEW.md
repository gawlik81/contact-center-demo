# Architecture Review — Contact Center Backend

> Data: 2026-04-26 | Analizowane: backend Spring Boot 3.3.5, Java 21
> Statystyki: 341 plików Java (53 557 linii produkcyjnych), 64 testy, 48 migracji Flyway

---

## Ogólna ocena

| Aspekt | Ocena | Komentarz |
|---|---|---|
| Struktura pakietów (DDD) | 8/10 | Czytelny podział, brakuje separacji interfejsów portów |
| Multi-tenancy (RLS) | 8/10 | PostgreSQL RLS + TenantContext, ryzyko przy virtual threads |
| Bezpieczeństwo | 7/10 | JWT RS256, BCrypt-12, ale publiczne endpointy bez rate limiting |
| Obsługa błędów | 7/10 | RFC 7807, brakuje kilku handlerów wyjątków |
| Testy | 7/10 | Testcontainers, brakuje chaos testów i metryki pokrycia |
| Jakość kodu | 7/10 | Brakuje MapStruct, serwisy lokalnie zbyt duże |
| Dokumentacja | 6/10 | Brakuje dev-setup.md i schemy kluczy Redis |
| **Ogółem** | **7/10** | Solidny monolit, wymaga refaktoringu przed skalowaniem |

---

## Krytyczne — bezpieczeństwo i integralność danych

### A1 — Virtual threads a InheritableThreadLocal (cross-tenant leakage)

`TenantContext` używa `InheritableThreadLocal`. W Java 21 virtual threads mogą dziedziczyć kontekst z carrier thread, co grozi wyciekiem danych między tenantami.

**Aktualny stan:** virtual threads wyłączone (`spring.threads.virtual.enabled=false`).

**Zalecenie:** Zmigrować na `ScopedValue` (Java 21+) zanim włączysz virtual threads.

```java
// Docelowe rozwiązanie
private static final ScopedValue<TenantSnapshot> TENANT = ScopedValue.newInstance();

public static void runWith(TenantSnapshot snapshot, Runnable task) {
    ScopedValue.where(TENANT, snapshot).run(task);
}
```

---

### A2 — Brak walidacji włączenia RLS przy starcie aplikacji

Jeśli `set_tenant_context()` nie zostanie wywołana, a PostgreSQL RLS nie jest włączone na tabeli — zapytania zwrócą dane wszystkich tenantów.

**Zalecenie:** Dodać `ApplicationReadyEvent` listener sprawdzający polityki RLS.

```java
@EventListener(ApplicationReadyEvent.class)
public void validateRlsPolicies() {
    List<String> missingRls = jdbcTemplate.queryForList(
        "SELECT tablename FROM pg_tables WHERE schemaname='public' " +
        "AND tablename NOT IN (SELECT tablename FROM pg_policies) " +
        "AND tablename LIKE 'cc_%'", String.class);
    if (!missingRls.isEmpty()) {
        throw new IllegalStateException("RLS not enabled on: " + missingRls);
    }
}
```

---

### A3 — Klucz szyfrowania email wyzerowany w DEV

`application-dev.yml` zawiera 64-znakowy klucz złożony z samych zer:

```yaml
email.encryption-key: "0000000000000000000000000000000000000000000000000000000000000000"
```

Nigdy nie może trafić do produkcji. Jeśli ktoś uruchomi profil `dev` na prod — dane będą szyfrowane pustym kluczem.

**Zalecenie:** Wymusić losowy klucz nawet w DEV. Dodać walidację przy starcie:

```java
@Value("${email.encryption-key}")
private String encryptionKey;

@PostConstruct
void validate() {
    if (encryptionKey.matches("0+")) throw new IllegalStateException("Weak encryption key");
}
```

---

### A4 — MapStruct zadeklarowany w pom.xml ale nieużywany

`pom.xml` zawiera dependency na MapStruct 1.6.2, jednak w kodzie nie ma ani jednego `@Mapper`. Mapowanie encji → DTO odbywa się ręcznie w serwisach, generując boilerplate i potencjalne błędy przy dodawaniu pól.

**Zalecenie:** Zaimplementować mappery — priorytet dla często używanych encji:
- `ContactMapper`
- `CampaignMapper`
- `CustomerMapper`
- `AppUserMapper`

---

### A5 — Duplikacja listy publicznych endpointów

Lista endpointów pomijających JWT występuje w dwóch miejscach:
- `SecurityConfig.java` — `requestMatchers`
- `TenantFilter.java` — `PUBLIC_PATH_PREFIXES`

Dodanie nowego publicznego endpointu w jednym miejscu bez aktualizacji drugiego powoduje błąd 500 lub 401.

**Zalecenie:** Wyodrębnić do wspólnego beana:

```java
@Configuration
public class PublicPathsConfig {
    public static final List<String> PUBLIC_PREFIXES = List.of(
        "/api/auth/login", "/api/auth/refresh",
        "/api/telephony/webhook/", "/ws/",
        "/actuator/health", "/api/oauth/"
    );
}
```

---

## Ważne — utrzymanie i stabilność

### B1 — IvrEngineService przekracza 82 KB

Jeden plik zawiera parsowanie drzewa IVR, renderowanie TwiML, obsługę Gather i logikę przejść. To naruszenie zasady pojedynczej odpowiedzialności i poważna przeszkoda w testowaniu.

**Zalecenie:** Rozdzielić na:
- `IvrTreeParser` — wczytywanie struktury z JSONB
- `TwiMLRenderer` — generowanie TwiML
- `IvrSessionManager` — śledzenie stanu rozmowy
- `IvrEngineService` — orkiestracja (lekki)

---

### B2 — Brak circuit breakera dla zewnętrznych adapterów

Brak mechanizmu odporności (retry, timeout, fallback) w:
- `TwilioTelephonyAdapter` — wywołania API VoIP
- `S3RecordingAdapter` — upload nagrań
- `EmailImapPoller` — polling IMAP

**Zalecenie:** Dodać Resilience4j (jest Spring Boot starter):

```java
@CircuitBreaker(name = "twilio", fallbackMethod = "fallbackCall")
@Retry(name = "twilio")
public void makeCall(CallRequest request) { ... }
```

Konfiguracja w `application.yml`:
```yaml
resilience4j.circuitbreaker.instances.twilio:
  slidingWindowSize: 10
  failureRateThreshold: 50
  waitDurationInOpenState: 30s
```

---

### B3 — GlobalExceptionHandler — brakujące handlery

Następujące wyjątki nie są obsługiwane i powodują błąd 500:

| Wyjątek | Oczekiwany status |
|---|---|
| `EntityNotFoundException` | 404 Not Found |
| `DataIntegrityViolationException` | 409 Conflict |
| `OptimisticLockingException` | 409 Conflict |
| `ConstraintViolationException` (Hibernate) | 422 |
| `TwilioRestException` | 502 Bad Gateway |

**Zalecenie:** Uzupełnić `GlobalExceptionHandler.java`.

---

### B4 — Brak optimistic locking (@Version) na encjach

Żadna encja nie ma pola `@Version`. Przy równoczesnych aktualizacjach (np. agent i supervisor modyfikują ten sam kontakt) zapis "ostatniego" wygrywa bez ostrzeżenia.

**Zalecenie:** Dodać na encjach z wysoką konkurencją:

```java
@Version
@Column(nullable = false)
private Long version = 0L;
```

Odpowiednia odpowiedź HTTP przy konflikcie: 409 przez handler `OptimisticLockingException`.

---

### B5 — RabbitMQ consumers bez TenantContext

Konsumenci wiadomości (`AuditLogConsumer`, `ProgressiveDialerService`) muszą ustawiać `TenantContext` z payloadu wiadomości przed każdym przetworzeniem.

**Zalecenie:** Wprowadzić `@TenantAwareListener` jako niestandardową adnotację lub bazową klasę konsumenta:

```java
public abstract class TenantAwareConsumer {
    protected void processWithTenant(UUID tenantId, Runnable task) {
        TenantContext.Snapshot snap = TenantContext.snapshot();
        try {
            TenantContext.setTenantId(tenantId);
            task.run();
        } finally {
            TenantContext.clear();
        }
    }
}
```

---

### B6 — Flyway seed V999 może zostać pominięty

`application-prod.yml` ma `out-of-order: false`. Jeśli ktoś przypadkowo uruchomi profil dev na produkcji, V999 zostanie pominięty (co jest dobrze), ale skrypt nie zostanie zablokowany, co może prowadzić do błędów jeśli inne komponenty na nim polegają.

**Zalecenie:** Dodać walidację profilu w `V999__dev_seed.sql`:

```sql
DO $$ BEGIN
  IF current_setting('app.profile', true) != 'dev' THEN
    RAISE EXCEPTION 'V999 seed can only run in dev profile';
  END IF;
END $$;
```

---

### B7 — Brak wersjonowania API

Wszystkie endpointy używają ścieżki `/api/...` bez wersji. Jakakolwiek zmiana breaking change wymaga jednoczesnej migracji klientów.

**Zalecenie:** Prefix `/api/v1/...` dla wszystkich nowych endpointów. Stare można utrzymać jako deprecated z nagłówkiem `Deprecation`.

---

## Dobre do wdrożenia — jakość i obserwowalność

### C1 — JaCoCo w CI/CD

Brak raportu pokrycia testów. Nie wiadomo które ścieżki kodu są nieprzetestowane.

**Zalecenie:** Dodać do `pom.xml` i wymagać minimum 80%:

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <configuration>
        <rules>
            <rule>
                <element>BUNDLE</element>
                <limits>
                    <limit>
                        <counter>LINE</counter>
                        <value>COVEREDRATIO</value>
                        <minimum>0.80</minimum>
                    </limit>
                </limits>
            </rule>
        </rules>
    </configuration>
</plugin>
```

---

### C2 — Brak dev-setup.md

Nowy developer nie wie:
- Jak generować klucze JWT (RS256)
- Co wpisać w zmienne Twilio placeholder
- Jak skonfigurować ngrok URL

**Zalecenie:** Stworzyć `docs/dev-setup.md` z checklistą onboardingową.

---

### C3 — Redis — brak dokumentacji kluczy i cache invalidation

Konwencja kluczy `{namespace}:{tenantId}:{entityType}:{id}` nie jest egzekwowana w kodzie. Cache invalidation przy zmianach encji jest niejasna.

**Zalecenie:**
1. Stworzyć `RedisKeyBuilder` utility class
2. Podpiąć invalidację cache w `AuditAspect` po zapisie

---

### C4 — Dead Letter Queue bez obsługi

Wiadomości trafiające do `cc.queue.dead-letter` nie są monitorowane ani replayowane.

**Zalecenie:** Dodać `DeadLetterConsumer` z alertingiem (log + metric counter):

```java
@RabbitListener(queues = "cc.queue.dead-letter")
public void handleDeadLetter(Message message) {
    log.error("Dead letter received: {}", message);
    meterRegistry.counter("rabbitmq.dead_letters").increment();
}
```

---

### C5 — OWASP Dependency Check brak w Maven

Zależności nie są skanowane pod kątem znanych CVE.

**Zalecenie:**

```xml
<plugin>
    <groupId>org.owasp</groupId>
    <artifactId>dependency-check-maven</artifactId>
    <version>10.0.3</version>
    <configuration>
        <failBuildOnCVSS>7</failBuildOnCVSS>
    </configuration>
</plugin>
```

---

## Plan wdrożenia

| Priorytet | Zadanie | Szacunek |
|---|---|---|
| 🔴 A3 | Walidacja klucza szyfrowania przy starcie | 1h |
| 🔴 A5 | Scalenie list publicznych endpointów | 2h |
| 🔴 A4 | Implementacja MapStruct mapperów | 1 dzień |
| 🟠 B3 | Uzupełnienie GlobalExceptionHandler | 2h |
| 🟠 B2 | Circuit breaker dla Twilio/S3/IMAP | 1 dzień |
| 🟠 B1 | Refaktoring IvrEngineService | 2 dni |
| 🟠 B4 | @Version na kluczowych encjach | 3h |
| 🟠 B5 | TenantAwareConsumer dla RabbitMQ | 3h |
| 🟡 A1 | Migracja na ScopedValue | 2 dni (po włączeniu virtual threads) |
| 🟡 A2 | RLS validation przy starcie | 3h |
| 🟡 C1 | JaCoCo w CI/CD | 2h |
| 🟡 C4 | DeadLetterConsumer | 2h |
| 🟡 C5 | OWASP Dependency Check | 1h |
| 🟡 B7 | API versioning /v1 | 1 dzień |
