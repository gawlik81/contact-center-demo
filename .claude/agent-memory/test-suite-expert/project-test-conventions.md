---
name: Project test conventions
description: Test patterns, conventions and helper setup specific to contact-center-demo
type: project
---

## Backend (Java/JUnit5/Mockito)

- All unit tests use `@ExtendWith(MockitoExtension.class)`. Integration tests with DB use `@DataJpaTest`.
- `@MockitoSettings(strictness = Strictness.LENIENT)` used in service tests with many shared mock setups.
- `TenantContext.setTenantId/setUserId/setUserRole` must be set in `@BeforeEach` for services that read context.
- `TenantContext.clear()` MUST be in `@AfterEach` — tests that skip this pollute context for sibling tests.
- `@Nested` classes used heavily to group scenarios (happy path / error / edge case).
- Test names follow pattern: `should_returnError_when_tenantMismatch()` or `methodName_scenario_expectedResult()`.
- `assertThat()` from AssertJ — never JUnit `assertEquals`.

## AuthService-specific

- `AuthService` constructor has 11 parameters — no `@InjectMocks`, manual construction in `@BeforeEach`.
- `blacklistAccessToken` uses `jwtParser.parseQuiet()` (not `parse()`). Mock `parseQuiet` in logout/verifyMfa/changePassword tests.
- `argThat` with lambda on typed parameter needs explicit type: `ArgumentMatchers.<AppUser>argThat(u -> ...)` to resolve ambiguity.
- `Tenant.builder().id(...).name(...)` — field is `id`, NOT `tenantId`.
- `MfaSetupResponse` fields: `secret()` and `qrCodeUri()` (not `qrCodeDataUri`).
- `MfaVerifyRequest` record has only one field: `code` (no `mfaToken`).

## Frontend (Angular/Vitest)

- `provideRouter([])` causes `NG04002` error when `router.navigate(['/auth/login'])` is called in tests.
- Fix: use `provideRouter([{ path: '**', children: [] }])` as catch-all route.
- `AuthService.logout()` fires a best-effort HTTP POST — tests must absorb it with `httpMock.expectOne(...).flush({})`.
- `CustomerLookupService` on 5xx errors calls `notifications.error()` and re-throws via `throwError()` — NOT returning null. Tests must use `await expect(promise).rejects.toThrow()`.
- `app.spec.ts` "should render title" test was broken — `App` component has no `<h1>`, only router-outlet. Fixed to check `router-outlet` presence instead.

**Why:** These were the patterns that caused compilation errors and test failures during the initial audit.
**How to apply:** Follow these patterns when writing new tests for this project.
