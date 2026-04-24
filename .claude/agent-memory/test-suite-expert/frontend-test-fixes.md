---
name: Frontend test fixes applied 2026-04-24
description: Bugs found and fixed in existing frontend tests during initial audit
type: feedback
---

Two existing frontend tests were broken before the audit:

1. **`app.spec.ts` – "should render title"**
   Test asserted `querySelector('h1')?.textContent` contains "Hello, contact-center-frontend".
   The `App` component has no `<h1>` — it only renders `<cc-toast-container>` and `<router-outlet>`.
   Fix: replaced with a test checking that `router-outlet` is present (the actual component structure).
   Also added `provideRouter([])` which was missing.

2. **`customer-lookup.service.spec.ts` – "should show an error toast and return null for non-404 HTTP errors"**
   Test expected the observable to emit `null` for 5xx errors.
   The service actually calls `notifications.error()` and then re-throws via `throwError(() => err)`.
   Fix: test now uses `await expect(resultPromise).rejects.toThrow()` and verifies `notifySpy.error` was called once.

**Why:** The service behavior (rethrow on 5xx) allows callers like `CustomerPanelComponent` to switch to an error state.
**How to apply:** When mocking services that use `throwError()` in catchError, expect promises to reject.
