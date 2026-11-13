---
name: be171-be172-shedlock
description: EPIC-33 faza 0 ShedLock (BE-171/BE-172) - wersja 6.10.0 dziala na Spring 6.1, konwencje blokad, pulapki testowe
metadata:
  type: project
---

ShedLock 6.10.0 (property `shedlock.version` w backend/pom.xml) dziala na Spring Boot 3.3.5 / Spring 6.1 (kontekst + prawdziwy JdbcTemplateLockProvider w testach Testcontainers). Konfiguracja: `infrastructure/config/ShedLockConfig` (PROXY_METHOD, order=HIGHEST_PRECEDENCE, proxyTargetClass=true, `@Qualifier("dataSource")` bo jest drugi DataSource ClickHouse).

**Why:** 24 metody `@Scheduled` (22 S, 2 T=dialer/routing BE-173, 0 I); tabela klasyfikacji w documentation/tech/04-backend.md sekcja 4.6, pilnowana przez `SchedulerLockCoverageTest`.

**How to apply:**
- `@SchedulerLock` w 6.x ma `lockAtMostFor`/`lockAtLeastFor` jako STRING (ISO `PT48S` lub placeholder `${...:PT10M}`), brak mnoznikow - dlatego klucze `shedlock.<job>.*` z inline defaultami.
- `lockAtLeastFor` ~0.8x interwalu (NIE rowny interwalowi: zegar bazy vs JVM -> pominiety wlasny przebieg; NIE 0.5x: dwie instancje w przeciwfazie = 2x na interwal).
- PROXY_METHOD blokuje tez BEZPOSREDNIE wywolanie metody na beanie Springa; testy jobow budowane przez `new` nie sa blokowane.
- Context Springa w testach zamyka bean DataSource (inferred destroy `close`) -> opakuj `DelegatingDataSource` (`ShedLockTestInstance`).
- Email polling: blokada programowa per tenant (`LockingTaskExecutor`, `EmailPolling_pollInbox_<tenantId>`), konstruktor EmailPollingServiceImpl ma dodatkowy arg (testy uzywaja `NoOpLockProvider.executor()`).
- `readAndLockSyncState`: usunieto martwy @Transactional i FOR UPDATE; wykluczanie cykli robi ShedLock.
- Lock rows przezywaja testy (lockAtLeastFor) - `deleteLocks` w @BeforeEach/@AfterEach.
- Otwarte: reczny trigger ETL (EtlStatusController -> syncTable) poza blokada; `WaitTimeEstimationServiceImpl.lastKnownAgentCounts` lokalny (BE-179).
