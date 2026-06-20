# EPIC-28: Per-Tenant Plugin (Extension) System — Plan realizacji

**Branch:** `tenant-plugin-system`
**Data planu:** 2026-06-20
**Status epica:** ⬜ Do zrobienia

---

## 1. Podsumowanie

| Metryka | Wartość |
|---------|---------|
| Tickety | 19 (DB-042…DB-045, BE-097…BE-107, FE-097…FE-100) |
| Fale wykonania | 8 |
| Złożoność łączna | XL (backend) + L (frontend) + S (database) |
| Kluczowa zależność | DB-042 → DB-045 (sekwencyjne migracje) blokują cały backend; BE-097 (`plugin-sdk`) blokuje wszystko poza nim w warstwie BE |

**Problem:** Tenanci chcą integrować platformę z własnymi systemami zewnętrznymi (CRM, ticketing, synchronizacja danych) oraz rozszerzać UI agenta/supervisora (przycisk, panel boczny) bez zmiany kodu backendu i bez własnego builda frontendu per tenant.

**Rozwiązanie:** Administrator tenanta uploaduje JAR z manifestem (`META-INF/plugin-manifest.json`) przez panel admina. JAR przechodzi walidację (checksum + statyczny skan bytecode ASM + schema manifestu), jest przechowywany w object storage (MinIO/S3), a po instalacji ładowany w runtime do dedykowanego `ClassLoader` per `(tenant_id, plugin_key)` — w tym samym JVM co aplikacja, ale odizolowany od `ApplicationContext`/JPA/Spring beans przez fasadę `PluginContext` (SDK). Plugin podłącza się do pięciu stałych punktów rozszerzeń (`PRE_CONTACT_CONNECT`, `POST_CONTACT_END`, `CUSTOMER_SYNC`, `DISPOSITION_SET`, `MANUAL_ACTION`) dispatchowanych przez `ExtensionPointPublisher` — blocking z timeoutem dla dwóch pierwszych/ostatniego, async przez RabbitMQ dla pozostałych. UI pluginu renderowane jest w sandboxed `<iframe>` cross-origin, komunikujące się z hostem Angular wyłącznie przez `postMessage`-owy `PluginUiSdk`.

**Źródło architektury:** `ARCHITECTURE.md` §11 (ADR-09…ADR-13, RT-09…RT-14), notatka projektowa `.claude/agent-memory/architecture-designer/epic_28_plugin_system.md`.

---

## 2. Graf zależności

```
DB-042 (V074: plugin, plugin_version — globalne, bez RLS)
  └─► DB-043 (V075: tenant_plugin_installation — RLS)
        └─► DB-044 (V076: tenant_plugin_extension_binding — RLS)
              └─► DB-045 (V077: plugin_invocation_log — RLS, partycjonowana)
                    │
                    ▼
BE-097 (plugin-sdk: PluginEntryPoint, PluginContext, DTO — bez Spring/JPA)
  ├─► BE-098 (Plugin/PluginVersion encje + PluginValidationService: manifest+checksum+ASM)
  │     └─► BE-099 (PluginUploadController + object storage JAR)
  │           └─► BE-100 (TenantPluginInstallation encja + PluginRegistrationService)
  │                 ├─► BE-101 (PluginRuntimeManager + PluginClassLoader + PluginContext impl)
  │                 │     ├─► BE-102 (ExtensionPointPublisher + PluginInvocationExecutor)
  │                 │     │     ├─► BE-103 (integracja PRE_CONTACT_CONNECT/MANUAL_ACTION — blocking)
  │                 │     │     └─► BE-104 (RabbitMQ async: POST_CONTACT_END/CUSTOMER_SYNC/DISPOSITION_SET)
  │                 │     └─► BE-105 (PluginInvocationLogService + REST log)
  │                 └─► BE-106 (PluginAdminController: enable/disable/rollback/REVOKED)
  │                       └─► BE-107 (serwowanie plugin-ui assets + manual-action proxy endpoint)
  │
  ▼ (FE startuje, gdy BE-099/BE-106 mają stabilne kontrakty REST)
FE-097 (PluginAdminService + modele TS)
  ├─► FE-098 (Strona admina: upload JAR, lista, health status, enable/disable, rollback)
  ├─► FE-099 (cc-plugin-panel-host: iframe sandboxed + PluginUiSdk postMessage)
  │     └─► FE-100 (Manual action button w toolbarze + side panel mount w agent desktop)
```

---

## 3. Fale wykonania

### Fala 1 — Baza danych: katalog globalny (blokuje wszystko)

| Ticket | Opis | Złożoność | Agent |
|--------|------|-----------|-------|
| **DB-042** | Migracja V074 — tabele `plugin`, `plugin_version` (globalne, bez RLS) | M | `db-schema-architect` |

**Wyjście:** plik `V074__create_plugin_catalog.sql` zastosowany na dev. Katalog globalny — żadna z tych dwóch tabel nie ma `tenant_id`/RLS (ADR-13): definicja pluginu jest współdzieloną metadaną infrastrukturalną, decyzja o jego instalacji per tenant jest tenant-scoped i zaczyna się od DB-043.

---

### Fala 2 — Baza danych: instalacje per tenant (sekwencyjne migracje)

| Ticket | Opis | Złożoność | Agent |
|--------|------|-----------|-------|
| **DB-043** | Migracja V075 — tabela `tenant_plugin_installation` (RLS) | M | `db-schema-architect` |

**Zależy od:** DB-042 (FK `plugin_version_id`).

---

### Fala 3 — Baza danych: bindingi punktów rozszerzeń

| Ticket | Opis | Złożoność | Agent |
|--------|------|-----------|-------|
| **DB-044** | Migracja V076 — tabela `tenant_plugin_extension_binding` (RLS) | S | `db-schema-architect` |

**Zależy od:** DB-043 (FK `tenant_plugin_installation_id`).

---

### Fala 4 — Baza danych: audit log wywołań (partycjonowana)

| Ticket | Opis | Złożoność | Agent |
|--------|------|-----------|-------|
| **DB-045** | Migracja V077 — tabela `plugin_invocation_log` (RLS, RANGE-partycjonowana miesięcznie po `invoked_at`) | M | `db-schema-architect` |

**Zależy od:** DB-043 (FK `tenant_plugin_installation_id`, `ON DELETE SET NULL`). Wzorzec partycjonowania identyczny z `audit_log`/`contact` (ARCHITECTURE.md §4.2-4.3).

**DDL dla DB-042…DB-045 gotowe w TASKS-DATABASE.md** — wystarczy skopiować i uruchomić.

---

### Fala 5 — Plugin SDK i walidacja uploadu (sekwencyjne, BE)

| Ticket | Opis | Złożoność | Agent |
|--------|------|-----------|-------|
| **BE-097** | Nowy moduł Maven `backend/plugin-sdk` — `PluginEntryPoint`, `PluginContext`, DTO (bez Spring/JPA) | M | `backend-dev-expert` |

**Wyjście:** `backend/plugin-sdk/pom.xml` jako nowy `<module>` w root `pom.xml`, obok `app`. Ten moduł jest **jedyną** zależnością compile-time, jaką potrzebuje deweloper pluginu firmy trzeciej.

---

### Fala 6 — Walidacja, upload, instalacja (łańcuch sekwencyjny)

| Ticket | Opis | Złożoność | Agent |
|--------|------|-----------|-------|
| **BE-098** | Encje `Plugin`/`PluginVersion` + `PluginValidationService` (manifest JSON Schema, checksum SHA-256, ASM static scan) | L | `backend-dev-expert` |
| **BE-099** | `PluginUploadController` + integracja object storage (MinIO/S3, wzorzec `S3Config`/`S3Properties`) | M | `backend-dev-expert` |
| **BE-100** | Encja `TenantPluginInstallation` + `PluginRegistrationService` (install/enable/disable/rollback/health) | L | `backend-dev-expert` |

**Zależy od:** BE-097 (BE-098 importuje `plugin-sdk` dla `PluginEntryPoint` przy weryfikacji `entryPointClass`). BE-099 zależy od BE-098. BE-100 zależy od BE-099.

---

### Fala 7 — Runtime, dispatch, integracja punktów rozszerzeń (częściowo równolegle)

| Ticket | Opis | Złożoność | Agent |
|--------|------|-----------|-------|
| **BE-101** | `PluginRuntimeManager` + `PluginClassLoader` + implementacja `PluginContext` (fasada SDK) | XL | `backend-dev-expert` |
| **BE-102** | `ExtensionPointPublisher` + `PluginInvocationExecutor` (bounded pool, timeouty, circuit breaker) | L | `backend-dev-expert` |
| **BE-103** | Integracja `PRE_CONTACT_CONNECT`/`MANUAL_ACTION` w przepływie połączenia (blocking, never-block-on-failure) | M | `backend-dev-expert` |
| **BE-104** | Async punkty rozszerzeń: `POST_CONTACT_END`/`CUSTOMER_SYNC`/`DISPOSITION_SET` przez RabbitMQ (`cc.queue.plugin-invocation`) + `PluginInvocationConsumer` | L | `backend-dev-expert` |
| **BE-105** | `PluginInvocationLogService` + REST `GET /api/supervisor/plugins/{installationId}/invocations` | S | `backend-dev-expert` |
| **BE-106** | `PluginAdminController` — enable/disable, rollback (przełączanie `enabled` między installation rows), platform `REVOKED` kill switch | M | `backend-dev-expert` |

**Zależy od:** BE-101 zależy od BE-100. BE-102 zależy od BE-101. BE-103 i BE-104 zależą od BE-102 (równolegle między sobą). BE-105 zależy od BE-102 (logowanie już istnieje po BE-102, ale REST do przeglądania wymaga zapisanych danych — w praktyce uruchamiane równolegle z BE-103/BE-104). BE-106 zależy od BE-101 (operuje na `PluginRuntimeManager.load/unload`).

---

### Fala 8 — UI pluginu i proxy manual-action (kończy backend)

| Ticket | Opis | Złożoność | Agent |
|--------|------|-----------|-------|
| **BE-107** | Endpoint serwujący `plugin-ui/` assety z JAR-a (dedykowana origin) + REST proxy `MANUAL_ACTION` dla `PluginUiSdk` | M | `backend-dev-expert` |

**Zależy od:** BE-103 (manual action musi już działać end-to-end po stronie backendu, zanim iframe SDK będzie miał co wołać) i BE-106 (assety serwowane tylko dla `enabled` installation).

---

### Fala 9 — Frontend: warstwa danych i panel admina (sekwencyjne)

| Ticket | Opis | Złożoność | Agent |
|--------|------|-----------|-------|
| **FE-097** | `PluginAdminService` + modele TypeScript (upload, lista, install/enable/disable, health) | S | `angular-frontend-expert` |
| **FE-098** | Strona „Ustawienia > Pluginy” (supervisor/admin) — upload JAR, lista instalacji, health status, enable/disable, rollback | L | `angular-frontend-expert` |

**Zależy od:** FE-097 zależy od BE-099 + BE-106 (kontrakty REST muszą być stabilne). FE-098 zależy od FE-097.

---

### Fala 10 — Frontend: UI pluginu w agent desktop (sekwencyjne)

| Ticket | Opis | Złożoność | Agent |
|--------|------|-----------|-------|
| **FE-099** | `cc-plugin-panel-host` — `<iframe sandbox="allow-scripts allow-forms">` cross-origin + `PluginUiSdk` (postMessage) | L | `angular-frontend-expert` |
| **FE-100** | Mount panelu bocznego i przycisku manual-action w agent desktop toolbar | M | `angular-frontend-expert` |

**Zależy od:** FE-099 zależy od BE-107 (assety + proxy endpoint muszą istnieć) i FE-097 (modele `installationId`/`mountPoint`). FE-100 zależy od FE-099.

---

## 4. Ryzyka i uwagi

| Ryzyko | Ocena | Mitygacja |
|--------|-------|-----------|
| RT-10: plugin złośliwy używa refleksji/manipulacji classloaderem, by ominąć izolację `ClassLoader`/SDK i dotrzeć do danych innego tenanta lub wewnętrznych beanów Springa — JDK nie ma sandboxa od deprecacji `SecurityManager` | Niskie-Średnie / Krytyczny wpływ | Wąski parent classloader (tylko interfejsy `plugin-sdk`), statyczny skan ASM odrzucający refleksję/`ProcessBuilder`/manipulację classloaderem przy uploadzie (BE-098), `PluginContext` nigdy nie zwraca beana/`EntityManager`/repozytorium, manualny review gate przed aktywacją dla niezweryfikowanych vendorów. Największe ryzyko rezydualne epika — eskalacja do izolacji proces/kontener jest opisaną ścieżką migracji (ADR-09), nie blokuje tego epika |
| RT-09: plugin wiesza się (infinite loop, blocked I/O) i wyczerpuje pool egzekutora pluginów | Średnie / Średni wpływ | Dedykowany bounded `PluginInvocationExecutor` (BE-102) odseparowany od poolów Tomcat/`@Async`/`@Scheduled`; `Future.get(timeout)` per wywołanie; circuit breaker po N kolejnych timeoutach izoluje blast radius do jednej instalacji |
| RT-12: wolne/niedostępne CRM zewnętrzne za `PRE_CONTACT_CONNECT` opóźnia połączenie telefoniczne | Średnie / Wysoki wpływ | Timeout 2s + explicit non-blocking-on-failure (BE-103) — connect telefonii nigdy nie czeka na plugin; circuit breaker per `(tenant, plugin, host)` na `HttpEgressClient` |
| RT-11: skompromitowany iframe UI pluginu (supply chain vendora) próbuje eksfiltrować dane widoczne agentowi | Średnie / Wysoki wpływ | Cross-origin iframe (nie web component same-origin, ADR-12), `sandbox="allow-scripts allow-forms"` bez `allow-same-origin`, CSP `connect-src` ograniczone do hostów z manifestu, `PluginUiSdk.getContext()` zwraca tylko minimalne ID, walidacja `event.origin` na hoście (FE-099) |
| RT-13: niepodpisany/niezweryfikowany JAR instalowany przed istnieniem wymogu signing (ADR-11, OQ-28-1 nierozwiązane) | Średnie / Wysoki wpływ | Statyczny skan + allow-list uprawnień manifestu jako gate Day-1; `granted_permissions` wymaga explicit zgody admina (nie auto-grant z manifestu); platform-level `REVOKED` kill switch (BE-106) do szybkiej globalnej reakcji |
| RT-14: dane wielu pluginów nadpisują się w `customer.custom_fields` | Niskie / Średni wpływ | SDK (BE-097/BE-101) wymusza namespaced JSONB path `custom_fields.plugins.<pluginKey>` — nigdy flat merge, nigdy istniejąca typowana kolumna (reguła anti-overloaded-column z CLAUDE.md) |
| Brak hard sandbox = ryzyko nie jest "rozwiązane", tylko "ograniczone proceduralnie/operacyjnie" | Świadomy trade-off architektoniczny (ADR-09) | Musi być zakomunikowane w umowie z vendorem pluginu — konsekwencja poza zakresem inżynieryjnym, do flagowania product/legal przed onboardingiem pierwszego nie-pilotowego tenanta |
| OQ-28-1 (signing JAR) nierozwiązane | Może zablokować produkcyjny rollout dla niezweryfikowanych vendorów | Decyzja security/compliance wymagana przed BE-098 wejściem na produkcję z nie-pilotowym tenantem — nie blokuje implementacji w tym epicu, flagowane jako follow-up |
| `PluginRuntimeManager` (BE-101) jest największym pojedynczym ticketem (XL) — ryzyko niedoszacowania | Średnie | Rozważyć rozbicie na pod-tickety przy starcie egzekucji, jeśli po analizie kodu okaże się zbyt duże na 1-3 dni; nie rozbijać na etapie planowania bez wglądu w realny szkielet `PluginClassLoader` |

---

## 5. Kryteria akceptacji epica

- [ ] **DB-042:** Migracja V074 aplikuje się bez błędów; `plugin`/`plugin_version` bez `tenant_id`/RLS (świadomie, ADR-13); `UNIQUE (plugin_id, version)`
- [ ] **DB-043:** Migracja V075; RLS+FORCE RLS na `tenant_plugin_installation`; `UNIQUE (tenant_id, plugin_version_id)`; `installation_config` szyfrowane AES-256-GCM (wzorzec `tenant_ai_config`)
- [ ] **DB-044:** Migracja V076; RLS+FORCE RLS na `tenant_plugin_extension_binding`; `UNIQUE (tenant_plugin_installation_id, extension_point)`
- [ ] **DB-045:** Migracja V077; RLS+FORCE RLS na `plugin_invocation_log`; partycjonowanie RANGE miesięczne po `invoked_at`; `related_contact_id` `ON DELETE SET NULL`
- [ ] **BE-097:** `plugin-sdk` kompiluje się jako niezależny moduł Maven, zero zależności od `spring-*`/`jakarta.persistence`; `PluginEntryPoint` z 7 metodami (2 wymagane + 5 default no-op)
- [ ] **BE-098:** Manifest JSON Schema waliduje wszystkie pola z ARCHITECTURE.md §11.2; ASM scan odrzuca blacklistowane pakiety; checksum mismatch → `REJECTED`; testy ≥8 scenariuszy walidacji
- [ ] **BE-099:** Upload >50MB odrzucony; non-JAR magic bytes odrzucony; JAR zapisany do object storage tylko gdy `VALIDATED`
- [ ] **BE-100:** Install tworzy nową `tenant_plugin_installation`; upgrade tworzy nowy wiersz (stary `enabled=false`, nie usuwany — rollback przez przełączenie flagi)
- [ ] **BE-101:** Każda instalacja `(tenant_id, plugin_key)` ma własny `ClassLoader`; parent classloader eksponuje tylko `com.contactcenter.pluginsdk.*`; brak ścieżki do `ApplicationContext`/JPA z poziomu pluginu — zweryfikowane testem negatywnym
- [ ] **BE-102:** `PluginInvocationExecutor` odseparowany od poolów request/async; `Future.get(timeout)` per extension point z domyślnymi wartościami z §11.7; circuit breaker `DEGRADED` po 5 kolejnych timeoutach/wyjątkach
- [ ] **BE-103:** `PRE_CONTACT_CONNECT` nigdy nie blokuje connect telefonii dłużej niż 2s i nigdy nie failuje connect na błędzie pluginu; `MANUAL_ACTION` timeout 5s
- [ ] **BE-104:** `POST_CONTACT_END`/`CUSTOMER_SYNC`/`DISPOSITION_SET` publikowane do `cc.queue.plugin-invocation`; `TenantContext.snapshot()/restore()/clear()` na granicy consumera (CLAUDE.md)
- [ ] **BE-105:** Każde wywołanie (SUCCESS/FAILED/TIMED_OUT/CIRCUIT_OPEN) zapisane do `plugin_invocation_log`; REST zwraca paginowaną historię
- [ ] **BE-106:** Disable usuwa bindingi z `PluginRegistry` natychmiast; `REVOKED` na `plugin_version` wyłącza wszystkie instalacje wszystkich tenantów niezależnie od ich `enabled`
- [ ] **BE-107:** Assety `plugin-ui/` serwowane z dedykowanej originy (nie same-origin z głównym SPA); manual-action proxy nie przyjmuje JWT z iframe — autoryzacja przez stronę hosta
- [ ] **FE-097:** Modele zgodne z kontraktami BE-099/BE-106; serwis `providedIn: 'root'`
- [ ] **FE-098:** Upload JAR z walidacją client-side (rozmiar/typ); lista instalacji z `health_status`; rollback w 1 kliknięciu (przełącza `enabled` między wersjami)
- [ ] **FE-099:** `<iframe sandbox="allow-scripts allow-forms">` bez `allow-same-origin`; host waliduje `event.origin` na każdej wiadomości; `PluginUiSdk.getContext()` nie eksponuje pełnego rekordu klienta/kontaktu
- [ ] **FE-100:** Przycisk manual-action widoczny tylko gdy plugin zadeklarował `manualActions` w manifeście i jest `enabled`/`HEALTHY`
- [ ] `mvn verify -pl app` oraz build `plugin-sdk` przechodzą (cały backend)
- [ ] `npm run lint && npm test` przechodzą (frontend)

---

## 6. Kolejność egzekucji krok po kroku

```
1. DB-042    → agent: db-schema-architect
               wynik: V074__create_plugin_catalog.sql

2. DB-043    → agent: db-schema-architect
               zależy od: DB-042 ✅
               wynik: V075__create_tenant_plugin_installation.sql

3. DB-044    → agent: db-schema-architect
               zależy od: DB-043 ✅
               wynik: V076__create_tenant_plugin_extension_binding.sql

4. DB-045    → agent: db-schema-architect
               zależy od: DB-043 ✅
               wynik: V077__create_plugin_invocation_log.sql

5. BE-097    → agent: backend-dev-expert
               zależy od: DB-045 ✅ (epik DB zamknięty, choć BE-097 nie ma FK do DB)
               wynik: moduł backend/plugin-sdk

6. BE-098    → agent: backend-dev-expert
               zależy od: BE-097 ✅
               wynik: Plugin/PluginVersion + PluginValidationService

7. BE-099    → agent: backend-dev-expert
               zależy od: BE-098 ✅
               wynik: PluginUploadController + object storage

8. BE-100    → agent: backend-dev-expert
               zależy od: BE-099 ✅
               wynik: TenantPluginInstallation + PluginRegistrationService

9. BE-101    → agent: backend-dev-expert
               zależy od: BE-100 ✅
               wynik: PluginRuntimeManager + PluginClassLoader + PluginContext impl

10. BE-102   → agent: backend-dev-expert
               zależy od: BE-101 ✅
               wynik: ExtensionPointPublisher + PluginInvocationExecutor

11. BE-103   → agent: backend-dev-expert  (równolegle z BE-104, BE-105)
    BE-104   → agent: backend-dev-expert
    BE-105   → agent: backend-dev-expert
               zależy od: BE-102 ✅

12. BE-106   → agent: backend-dev-expert
               zależy od: BE-101 ✅ (może równolegle z falą 11)

13. BE-107   → agent: backend-dev-expert
               zależy od: BE-103 ✅ + BE-106 ✅

14. FE-097   → agent: angular-frontend-expert
               zależy od: BE-099 ✅ + BE-106 ✅
               wynik: PluginAdminService + modele

15. FE-098   → agent: angular-frontend-expert
               zależy od: FE-097 ✅

16. FE-099   → agent: angular-frontend-expert
               zależy od: BE-107 ✅ + FE-097 ✅
               wynik: cc-plugin-panel-host + PluginUiSdk

17. FE-100   → agent: angular-frontend-expert
               zależy od: FE-099 ✅
```

---

## 7. Komendy weryfikacji

```bash
# Backend po każdej fali (uwaga: nowy moduł plugin-sdk od BE-097):
cd backend && mvn verify

# Tylko app (jeśli plugin-sdk jeszcze nie istnieje, np. przed BE-097):
cd backend && mvn verify -pl app

# Frontend po każdej fali:
cd frontend && npm run lint && npm test

# Uruchomienie lokalne:
docker compose --env-file .env.local-demo -f docker-compose.yml -f docker-compose.local-demo.yml up -d --remove-orphans
cd backend/app && mvn spring-boot:run -Dspring-boot.run.profiles=dev
cd frontend && npm start
```

---

## 8. Następny krok

```
Wykonaj: DB-042 — migracja V074 (tabele plugin, plugin_version)
Komenda: /execute-ticket DB-042
Agent:   db-schema-architect
```
