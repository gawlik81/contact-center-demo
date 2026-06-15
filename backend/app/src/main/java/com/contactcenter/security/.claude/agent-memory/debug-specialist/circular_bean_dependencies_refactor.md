---
name: circular_bean_dependencies_refactor
description: Multiple chained Spring bean circular-dependency cycles surfaced during domain package refactor (branch `refactor`) — pattern and fix convention
metadata:
  type: project
---

During the `domain.*` package-per-domain refactor (branch `refactor`, June 2026), the
first real `docker compose up --build backend` after the contact/campaign encapsulation
pass (commit `1680d2d`) revealed **a chain of FIVE separate circular bean dependency
cycles**, each only visible after the previous one was fixed (Spring reports only the
first cycle it detects, then fails fast).

**Why this happened:** `ContactServiceImpl` sits at the center of many eager
constructor-injected dependencies (`RecordingService`, `TwilioTelephonyAdapter`,
`TwilioRecordingDownloadService`, `TenantServiceImpl` transitively, etc.), and several
of those dependencies also need `ContactService`/`UserService` back — eagerly. As long
as `UserServiceImpl` didn't have a `PasswordEncoder` bean defined inside `SecurityConfig`
(which itself depends on `JwtAuthFilter` → `UserDetailsServiceImpl` → `UserService`),
the cycle never closed and Spring's circular-reference detection never triggered. Once
that final link (`UserServiceImpl` → `PasswordEncoder` bean in `SecurityConfig`) was
added/discovered, ALL the dormant cycles became fatal one by one.

**Fixes applied (all using the project's existing `@Lazy @Autowired` setter convention,
see `[[lazy_setter_convention]]`):**
1. Extracted `PasswordEncoder` bean from `SecurityConfig` into new
   `security/PasswordEncoderConfig.java` (no filter dependencies → breaks the
   `UserServiceImpl → PasswordEncoder → SecurityConfig → JwtAuthFilter → UserDetailsServiceImpl → UserServiceImpl` cycle).
2. `domain/service/RecordingService.java`: `ContactService` field converted from
   constructor injection to `@Lazy @Autowired setContactService()`.
3. `domain/telephony/TwilioTelephonyAdapter.java`: `ContactService` field converted
   to `@Lazy @Autowired setContactService()` (mirrors the existing `setUserService`
   pattern already in that class).
4. `domain/tenant/TenantServiceImpl.java`: `UserService` field converted to
   `@Lazy @Autowired setUserService()` (mirrors existing `setAdminMetricsService`
   pattern in same class).
5. `domain/service/TwilioRecordingDownloadService.java`: `ContactService` field
   converted to `@Lazy @Autowired setContactService()`.

**How to apply / debug similar future cycles:** When Spring reports
"The dependencies of some of the beans ... form a cycle", fix ONE cycle at a time,
rebuild, restart `docker compose ... up -d --build backend`, and re-check logs —
expect MORE cycles to surface sequentially. Don't assume one fix solves it. Always
use `@Lazy @Autowired` setter on the LEAST central class in the cycle (i.e. NOT
`ContactServiceImpl`/`UserServiceImpl`, which are hubs many things depend on) —
follow existing in-file comments documenting prior `@Lazy` cycle-breaks, they hint
at the convention. After each fix, also check for `new XxxServiceImpl(...)` direct
constructor calls in unit tests (Mockito `@InjectMocks` doesn't call setters for
non-final fields — must call `service.setXxx(mock)` manually in test setup).

**Status:** All 5 cycles fixed on branch `refactor`. 1442/1442 backend tests pass
(except a known pre-existing flaky test `SupervisorMetricsServiceTest$KpiCallsInIvrTests`,
unrelated — fails intermittently on baseline too). Backend starts healthy in
`docker compose ... up -d --build backend`.
