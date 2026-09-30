---
name: feedback-jpa-real-db-integration-test-harness
description: Jak testować package-private repozytoria/serwisy na prawdziwym Postgresie (Testcontainers + pełny Flyway + prawdziwy Hibernate/@Transactional) — gotowy harness w src/test/.../support i pułapki
metadata:
  type: feedback
---

Od BE-125 istnieje harness do testów repozytoriów/serwisów na PRAWDZIWEJ bazie (starsza notka „projekt nie ma Testcontainers dla testów repozytoriów" jest nieaktualna):
- `com.contactcenter.support.PostgresTestDatabase` — singleton `postgres:16-alpine` per JVM + Flyway `classpath:db/migration`, `superuserPool(n)`, `pool(user,pw,n)`, `createRestrictedLoginRole(...)` (rola LOGIN bez BYPASSRLS, członek `app_user`), `insertTenant(...)`.
- `com.contactcenter.support.JpaTestContext.create(dataSource, entityTypes, beanClasses, prepareHook)` — minimalny `AnnotationConfigApplicationContext`: prawdziwy Hibernate + `JpaTransactionManager` + `@EnableTransactionManagement(proxyTargetClass = true)`; klasy package-private podajesz jako `X.class` z testu w tym samym pakiecie; mocki (np. `S3Client`) rejestrujesz w hooku przez `ctx.getBeanFactory().registerSingleton(...)`.
- `com.contactcenter.support.TestcontainersSupport.ensureDockerApiVersion()` — wymuszenie `api.version=1.44` (Docker API 1.32 jest odrzucane); wołać przed każdym kontenerem, także `GenericContainer` (MinIO: `minio/minio:latest`, `Wait.forHttp("/minio/health/ready")` — bez nowej zależności).
- Wzorce: `EmailMessagePurgeIntegrationTest` (fake S3 + pula rozmiaru 1), `EmailMessagePurgeRlsIntegrationTest`, `SocialMessagePurgeIntegrationTest`, `EmailAttachmentStorageServiceMinioTest`.

**Why:** mocki `EntityManager` nie łapały błędów natywnego SQL/Hibernate/`TenantContext`/RLS (lekcje EPIC-29); harness pozwolił złapać m.in. „ciche 0 wierszy" `DELETE` pod rolą bez BYPASSRLS oraz udowodnić brak transakcji wokół I/O S3.

**How to apply:**
- Dane zostają między klasami (współdzielony kontener) — zawsze losowi tenanci; nie zakładaj pustych tabel; po ciężkich seedach (EXPLAIN 30 tys. wierszy) sprzątaj `DELETE … WHERE tenant_id`.
- „Żadne połączenie DB nie jest trzymane w trakcie I/O": pula Hikari rozmiaru 1 + `getHikariPoolMXBean().getActiveConnections()` w odpowiedzi fałszywego S3 (musi być 0).
- EXPLAIN dokładnie produkcyjnego SQL: trzymaj SQL w package-private `static final String` w repozytorium i podstawiaj literały za `:param`. Dla małych tabel seeduj ≥30 tys. wierszy + `ANALYZE`, inaczej planner wybierze Seq Scan.
- Asercja „brak ctid": regex `\bctid\b`, NIE `containsIgnoringCase("ctid")` — parametr `:contactIds` zawiera podciąg „ctId".
- Testy RLS asertuj inwariantem (deletedRows == faktycznie zniknęło, kontakty z pozostałą wiadomością ∈ blocked), nie bieżącym stanem polityk — nie pękną po DB-064.
Powiązane: [[feedback-repository-tests]] (starszy styl z mockiem EM — nadal OK dla prostych delegacji), [[feedback-partitioned-table-ctid-delete-pitfall]].
