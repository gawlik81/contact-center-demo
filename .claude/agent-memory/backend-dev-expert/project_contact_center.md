---
name: contact_center_backend_project
description: Informacje o projekcie Contact Center SaaS – stack backendowy, struktura Maven, konwencje, decyzje architektoniczne
type: project
---

Projekt: Wielokanałowa platforma Contact Center w modelu SaaS (multi-tenant).
Stack backend: Java 21 + Spring Boot 3.3.5, Maven multi-module, PostgreSQL 16, Redis 7, RabbitMQ 3.13.
Flyway: migracje V001-V014 już gotowe (DB-001 ukończone). Nowe migracje od V015+.
AI serwis: Python 3.12 + FastAPI (osobny runtime).

**Why:** PRD v1.0 z 2026-03-12. Faza 1 = MVP, modularny monolit. Mikroserwisy w Fazie 2.

**How to apply:** Przy kolejnych zadaniach BE zakładaj że BE-001 jest ukończone – struktura Maven, konfiguracja Spring Boot, profile dev/prod są gotowe.

## Struktura Maven
- Root POM: `backend/pom.xml` (groupId: com.contactcenter, version: 1.0.0-SNAPSHOT)
- Moduł app: `backend/app/pom.xml` – główna aplikacja Spring Boot
- Migracje Flyway: `backend/src/main/resources/db/migration/` (współdzielone)
- Seed DEV: `backend/src/main/resources/db/seed/V999__dev_seed.sql`

## Struktura pakietów Java
- `com.contactcenter.app` – klasa główna ContactCenterApplication
- `com.contactcenter.api` – kontrolery REST, DTO, GlobalExceptionHandler
- `com.contactcenter.domain` – encje JPA, serwisy domenowe, repozytoria
- `com.contactcenter.infrastructure.config` – klasy @Configuration
- `com.contactcenter.security` – Spring Security, JWT, TenantContext

## Kluczowe klasy konfiguracyjne (już zaimplementowane)
- `FlywayConfig` – strategia migracji per profil (dev/prod)
- `RedisConfig` – RedisTemplate, RedisCacheManager, stałe TTL i CacheNames
- `RabbitMQConfig` – exchanges (cc.events, cc.audit, cc.notifications, cc.dlx), kolejki, bindingi
- `OpenApiConfig` – Swagger UI, Bearer JWT security scheme
- `AsyncConfig` – ThreadPoolTaskExecutor dla @Async (core=4, max=16)
- `SecurityConfig` – stub BE-001 (pełna implementacja JWT w BE-003)
- `TenantContext` – ThreadLocal UUID per wątek HTTP (czyszczony przez TenantFilter w BE-002)
- `GlobalExceptionHandler` – RFC 7807 Problem Details

## Konwencje RabbitMQ
- Exchange names: cc.events, cc.audit, cc.notifications, cc.dlx
- Queue names: cc.queue.{name} (np. cc.queue.call-events)
- Routing keys: {aggregate}.{event} (np. call.incoming, agent.status.changed)
- Stałe w klasie RabbitMQConfig

## Konwencje Redis
- Klucze: {namespace}:{tenantId}:{entityType}:{id}
- TTL stałe: RedisCacheConfig.TTL_* (JWT_BLACKLIST=16min, AGENT_PRESENCE=30s, QUEUE_STATS=5s...)
- Cache names: RedisCacheConfig.CacheNames.* (QUEUE_STATS, ADMIN_METRICS, CLI_LOOKUP...)

## Profile Spring Boot
- dev: lokalna baza jdbc:postgresql://localhost:5432/contact_center_dev (postgres/postgres), show-sql=true
- prod: wyłącznie ENV vars (DB_URL, DB_USERNAME, DB_PASSWORD, REDIS_*, RABBITMQ_*)
- test: Flyway wyłączony, RabbitMQ i Redis auto-config wyłączone

## Docker Compose (lokalne dev)
- `docker-compose.yml` w root projektu
- PostgreSQL 16: localhost:5432 (contact_center_dev/postgres/postgres)
- Redis 7: localhost:6379
- RabbitMQ 3.13: localhost:5672 AMQP, localhost:15672 Management UI (guest/guest)

## Bezpieczeństwo
- BCrypt cost=12 (skonfigurowany w SecurityConfig)
- JWT: STATELESS sessions, CSRF disabled
- TenantContext ThreadLocal: MUSI być czyszczony w finally bloku filtra
- Błędy dostępu cross-tenant: HTTP 403 (nie 404) – wymóg BE-002

## Status zadań
- BE-001: UKOŃCZONE – struktura Maven, konfiguracja Spring Boot, profile dev/prod
- BE-002: UKOŃCZONE – TenantContext, TenantFilter, JwtParser, TenantAwareRepository, CrossTenantAspect, SecurityConfig, GlobalExceptionHandler; 85 testów jednostkowych zielonych
- BE-003: UKOŃCZONE – JwtService (sign RS256), JwtAuthFilter (SecurityContext), TokenBlacklistService (Redis), MfaService (TOTP RFC 6238), AuthService, AuthController, AppUser+RefreshToken encje; 132 testy zielone
- BE-004: UKOŃCZONE – LoginRateLimiter (Redis INCR+EXPIRE, 5 prób/15 min/IP, HTTP 429), passwordResetRequired w LoginResponse, POST /api/auth/change-password, POST /api/auth/force-reset/{userId} (ADMIN/SUPERVISOR @PreAuthorize); 146 testów zielonych (+14 nowych)
- BE-006: UKOŃCZONE – Tenant.java (encja JPA, JSONB config przez JsonMapConverter), TenantRepository (JPA bez RLS), TenantService (CRUD+deactivate), TenantResourceLimitService (checkAgentLimit/checkQueueLimit/checkCampaignLimit+LimitCheckResult), TenantController (6 endpointów), ResourceLimitExceededException (HTTP 422 z resourceType/limit/current), GlobalExceptionHandler rozszerzony o ResourceLimitExceededException + EntityNotFoundException; 173 testów PASS (+27 nowych)
- BE-010: UKOŃCZONE – S3Properties (@ConfigurationProperties prefix=s3), S3Config (S3Client+S3Presigner beany), ContactRepository (natywny JdbcTemplate dla partycjonowanej tabeli), RecordingService (@RabbitListener call.hangup, uploadToS3 SSE-AES256, presigned URL), RecordingRetentionJob (@Scheduled cron 02:00 UTC, retencja 90 dni), RecordingController (GET /api/recordings/{contactId}, SUPERVISOR+ADMIN), V022 migracja; AWS SDK v2 2.28.29 (BOM import); 316 testów PASS

## Nowe migracje Flyway
- Kolejne migracje: od V023+ (V022 = indeks retencji nagrań BE-010)
- V021 = add_offline_status (status OFFLINE dla app_user)
- V018 = dodano is_active BOOLEAN do app_user (wymagane przez BE-003 UserDetailsServiceImpl)
- Uwaga: migracje V003 i BE-003 encje miały rozbieżności -- naprawione (patrz niżej)

## Kluczowe mapowania encji JPA (po naprawie BE-003 bug)
- AppUser.id → kolumna `user_id` (nie `id`!) – PK tabeli app_user
- RefreshToken.id → kolumna `token_id` (nie `id`!) – PK tabeli refresh_token
- RefreshToken.token → kolumna `token_hash` (raw UUID przechowywany bez haszowania)
- AppUser.active → kolumna `is_active` (dodana migracją V018)

## @EnableJpaRepositories + @EntityScan – wymagane w ContactCenterApplication
Problem: Spring Data JPA + Redis razem = strict mode = Found 0 JPA repository interfaces
Rozwiązanie (już wdrożone w ContactCenterApplication.java):
  @EnableJpaRepositories(basePackages = "com.contactcenter.domain.repository")
  @EntityScan(basePackages = "com.contactcenter.domain.model")

## BE-003 – szczegóły implementacji
- JwtService: podpisuje tokeny kluczem prywatnym RSA (PKCS#8 PEM), claims: userId/tenantId/role/email/mfaVerified
- JwtAuthFilter: działa PRZED TenantFilter; ustawia SecurityContext; sprawdza Redis blacklistę
- TokenBlacklistService: klucz Redis = jwt:blacklist:{sha256(token)}, StringRedisTemplate
- MfaService: dev.samstevens.totp 1.7.1, DefaultSecretGenerator(32) = 32 znaki Base32, okno ±1 krok
- AuthService: login (bcrypt+AuthenticationManager), refresh (token rotation), logout (blacklista+revoke), MFA setup/verify
- UserDetailsServiceImpl: username = "{tenantId}:{email}" (separator ":")
- AppUserDetails: opakowuje AppUser, rola = "ROLE_{ROLE_NAME}", dodano pole `role` (String bez prefiksu ROLE_)
- SecurityConfig zaktualizowany: JwtAuthFilter + DaoAuthenticationProvider + AuthenticationManager bean + CORS
- GlobalExceptionHandler rozszerzony: BadCredentialsException, DisabledException, InvalidTokenException, MfaException, IllegalStateException

## BE-004 – szczegóły implementacji
- LoginRateLimiter: Redis INCR+EXPIRE atomowe, klucz rate:login:{ip}, TTL 900s, max 5 prób; reset() po udanym login
- AuthService.login() rozszerzony: parametr ip (przekazywany z HttpServletRequest.getRemoteAddr()), rate limiting PRZED autentykacją, reset PRZED zwróceniem odpowiedzi, priorytet: passwordResetRequired > mfaRequired
- LoginResponse rozszerzony: nowe pole `passwordResetRequired` (Boolean, @JsonInclude NON_NULL), factory method passwordResetRequired()
- AuthService.changePassword(): weryfikacja bcrypt, walidacja siły (cyfra+wielka litera), updatePasswordAndClearReset(), blacklista stary token, revoke wszystkich refresh tokenów, nowe tokeny
- AuthService.forcePasswordReset(): SUPERVISOR może resetować tylko własny tenant (cross-tenant → AccessDeniedException), unieważnia sesje
- GlobalExceptionHandler rozszerzony: RateLimitExceededException → 429 + Retry-After: 900, IllegalArgumentException → 422
- Testy: LoginRateLimiterTest (6 testów, mock StringRedisTemplate), AuthServiceChangePasswordTest (6+2 testów)

## BE-006 – szczegóły implementacji
- Tenant.java: encja mapująca tabelę `tenant` (PK: tenant_id, status: TenantStatus enum, config: JSONB)
- JsonMapConverter: JPA AttributeConverter<Map<String,Object>, String> – serializacja JSONB bez hypersistence-utils
- TenantRepository: JPA (NIE rozszerza TenantAwareRepository – tabela `tenant` nie ma RLS per-tenant)
  - countActiveAgentsByTenantId: native query na app_user (role='AGENT', is_deleted=false)
  - countActiveQueuesByTenantId: native query na queue (is_active=true, bez is_deleted – tabela queue nie ma tej kolumny)
  - countActiveCampaignsByTenantId: native query na campaign (status NOT IN STOPPED/COMPLETED, bez is_deleted)
- TenantService.deactivateTenant(): ustawia status=INACTIVE + appUserRepository.findAll() z filter po tenantId → setActive(false)
- TenantResourceLimitService: LimitCheckResult record (isExceeded(), available()) do użycia w dashboardach
- ResourceLimitExceededException: pola resourceType/limit/current; GlobalExceptionHandler → 422 z property "error"="RESOURCE_LIMIT_EXCEEDED"
- SecurityConfig: dodano requestMatchers("/api/tenants/**").hasRole("ADMIN")
- Endpointy: POST /api/tenants, GET /api/tenants, GET /api/tenants/{id}, PATCH /api/tenants/{id}, POST /api/tenants/{id}/deactivate, GET /api/tenants/check-name, GET /api/tenants/{id}/check-name
- @PreAuthorize("hasRole('ADMIN')") na poziomie klasy TenantController
