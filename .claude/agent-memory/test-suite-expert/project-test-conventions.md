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
- Runner is `ng test` via `@angular/build:unit-test` (Vitest under the hood) — `vi`, `describe`, `it`, `expect` are globals, no import needed. Matches every existing `.spec.ts` in this repo.

### Native `<dialog>` component testing pattern

jsdom (checked: v28.1.0, the version pinned here) does NOT implement `HTMLDialogElement.showModal()`/`close()` — calling them throws `is not a function`. Established repo pattern (see `customer-create-modal.component.spec.ts`, `campaign-import.component.spec.ts`, and now `social-integrations.component.spec.ts`):
```ts
beforeAll(() => {
  if (!HTMLDialogElement.prototype.showModal) {
    HTMLDialogElement.prototype.showModal = function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  }
  if (!HTMLDialogElement.prototype.close) {
    HTMLDialogElement.prototype.close = function (this: HTMLDialogElement) {
      this.removeAttribute('open');
    };
  }
});
```
To assert a component actually called `showModal()`/`close()` on its `@ViewChild` dialog (the ViewChild field is typically `private`, so don't reach into it with `(component as any)`): query the *same* rendered DOM node via `fixture.nativeElement.querySelector('dialog.some-unique-class')` and `vi.spyOn(el, 'showModal')` — it's the identical element instance the component's ElementRef wraps, so the spy fires. No need for a testing/harness abstraction here.

- Components that call `transloco.translate()` directly in TS (not just the template pipe) still just need `TranslocoTestingModule.forRoot({ langs: {...}, translocoConfig: {...} })` — missing keys don't throw (transloco logs and falls back to the key), so only bother filling in keys relevant to what the test actually renders/reads; don't chase 100% key coverage.
- To test a "guard against concurrent submit while a request is in flight" scenario, mock the HTTP-returning method with `new Subject()` (not `of(...)`) so the observable doesn't resolve synchronously — this lets you assert the loading signal is `true` and call the submit method a second time before completing the subject, proving the second call was rejected by the guard.

**Why:** These were the patterns that caused compilation errors and test failures during the initial audit.
**How to apply:** Follow these patterns when writing new tests for this project.
