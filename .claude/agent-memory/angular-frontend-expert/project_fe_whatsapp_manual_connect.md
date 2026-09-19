---
name: fe_whatsapp_manual_connect
description: WhatsApp manual-credentials connect dialog replacing external link on Social Integrations page (2026-08-29), plus CR-FRONTEND follow-up fixes (same day)
metadata:
  type: project
---

Replaced the WhatsApp "disconnected" card's external `<a href="https://business.facebook.com/settings/whatsapp-business-accounts">`
link with a "Połącz" button opening a native `<dialog>` form (phoneNumberId, accessToken, displayName required;
businessAccountId optional), posting to `POST /api/integrations/WHATSAPP/connect`. FACEBOOK/INSTAGRAM keep the
existing OAuth `connectPlatform()` flow untouched; only WhatsApp's dead branch in `connectPlatform()` was removed
since it's now unreachable (button now calls `openWhatsappConnectDialog()` directly).

**Files touched:**
- `frontend/src/app/features/integrations/pages/social-integrations/social-integrations.component.ts` — added
  `whatsappConnectDialogRef` ViewChild, signals (`connectingWhatsapp`, `whatsappFormSubmitted`,
  `whatsappPhoneNumberId/AccessToken/DisplayName/BusinessAccountId`), `whatsappFormValid` computed,
  `openWhatsappConnectDialog/closeWhatsappConnectDialog/onWhatsappFormSubmit/submitWhatsappConnect`.
- `.html` — removed the old `.whatsapp-notice` block from the card body (was "click button below" copy tied to
  the external link) and added a new `#whatsappConnectDialogRef` dialog containing the same notice (now with the
  Meta Business Suite link inline) plus the form.
- `.scss` — added generic `.form-field` block (label/input/textarea/hint/error/optional/required) and
  `.cc-dialog--form` (max-width 520px) — first form styling in this module.
- `models/social-integration.model.ts` — added `WhatsAppConnectRequest`.
- `services/social-integration.service.ts` — added `connectWhatsApp()`.
- `public/i18n/{pl,en,de,uk}.json` — repurposed `whatsappNote`/`whatsappCta` text for dialog context (whatsappCta
  is now the link label, not "click button below"), added `whatsappConnectDialogTitle`, `phoneNumberIdLabel`,
  `accessTokenLabel`, `accessTokenHint`, `displayNameLabel`, `businessAccountIdLabel`, `fieldRequired`. Verified
  key-set parity across all 4 files via a Python set-diff (see [[project_epic29_data_retention]] for the lockstep
  i18n convention this follows).

**Decision — no Reactive/Template Forms:** this module (`features/integrations`) had zero forms before this
change (`imports: [TranslocoModule]` only). Per explicit user instruction, kept it that way rather than pulling
in `ReactiveFormsModule`: text fields bind via `[value]="sig()"` + `(input)="sig.set($any($event.target).value)"`,
required-ness is `computed()`, and errors show only after a submit attempt (`whatsappFormSubmitted` signal set in
the submit handler, checked in template alongside each field's emptiness). The `$any($event.target).value` cast
without importing forms module has one confirmed precedent elsewhere in the repo:
`agent/pages/customers/adhoc-email-modal/adhoc-email-modal.component.html`. Submit button lives outside the
`<form>` (in the dialog footer actions row) and links via `type="submit" form="whatsapp-connect-form"`, with the
form itself using plain `(submit)` (not `(ngSubmit)`, which needs FormsModule) calling
`onWhatsappFormSubmit(event)` → `event.preventDefault()` + `submitWhatsappConnect()`. This whole
signals-only-form pattern is reusable for any other single-form dialog in a module that hasn't adopted Reactive
Forms yet — don't default to `ReactiveFormsModule` just because most other modules use it (e.g.
[[project_fe045_add_break_modal]] does use full `FormBuilder`/`ReactiveFormsModule` — that's the `agent` module's
convention, not a repo-wide rule).

No `.spec.ts` existed for `SocialIntegrationsComponent` before this change and none was added — per instructions,
only extend an existing spec file, never create a fresh full suite as a side effect of an unrelated feature task.

Verification: `npm run lint` (0 errors, only pre-existing unrelated `no-console` warnings),
`npm run format` / `format:check` clean, and `ng build --configuration development` succeeded (confirms the
template type-checks, including the `$any()` casts and the cross-element `form="whatsapp-connect-form"` wiring).

## CR-FRONTEND follow-up round (same day, 2026-08-29) — tasks D/E/F/G

Fixed 4 non-blocking review findings from `CR-FRONTEND.md` on this same feature:

- **D (double error toast):** `errorHandlerInterceptor` fires a global toast on every `HttpErrorResponse` unless
  the request carries `SKIP_ERROR_TOAST` (`core/interceptors/error-handler.interceptor.ts`). The component's own
  `catchError` blocks in `connectPlatform()`, `confirmDisconnect()`, and `submitWhatsappConnect()` each show a more
  specific toast, so all three underlying service calls now pass
  `{ context: new HttpContext().set(SKIP_ERROR_TOAST, true) }` — `initiateOAuth()`, `deleteIntegration()`,
  `connectWhatsApp()` in `services/social-integration.service.ts`. `getIntegrations()`/`loadIntegrations()` was
  deliberately left alone — not in the reviewer's scope for this fix. Existing precedent for this exact pattern:
  `features/agent/services/softphone.service.ts` (`initializeTwilioDevice()`).
- **E (plaintext token reveal/hide):** No password-reveal pattern existed anywhere in the app before this change
  (checked `features/auth/{login,change-password}` — both use plain `type="password"` with no toggle). Chose
  option (a) from the ticket: converted the `<textarea>` access-token field to a single-line `<input
  [type]="visible() ? 'text' : 'password'">` (Meta permanent tokens are a single continuous string, no newlines),
  with a `whatsappAccessTokenVisible` signal and a `toggleWhatsappAccessTokenVisibility()` method, toggled by an
  eye/eye-slash icon button (`.form-field__input-action`, absolutely positioned inside a new
  `.form-field__input-group` wrapper) with `aria-label` via new i18n keys `showTokenLabel`/`hideTokenLabel`. Reset
  to hidden on every `openWhatsappConnectDialog()`. This is the **first reveal/hide-toggle instance in the repo** —
  future password-like fields can reuse this `.form-field__input-group` / `.form-field__input-action` CSS pattern
  instead of inventing a new one.
- **F (client-side length validation):** Added `readonly maxFieldLength = 255` (mirrors backend's
  `@Size(max = 255)` on `WhatsAppConnectRequest.phoneNumberId/displayName/businessAccountId` — `accessToken` has
  no backend limit, left uncapped per the ticket). `whatsappFormValid` computed now also rejects >255 on those
  three fields; template adds `[attr.maxlength]="maxFieldLength"` and a `fieldTooLong` error message (new i18n key,
  interpolated as `{{ 'integrations.social.fieldTooLong' | transloco: { max: maxFieldLength } }}`) shown after
  submit attempt, alongside the existing `fieldRequired` message.
- **G (Escape bypasses in-flight guard):** `closeWhatsappConnectDialog()` guards on `connectingWhatsapp()` but only
  the Cancel button called it — native Escape fires the dialog's `cancel` event directly, bypassing the guard.
  Added `(cancel)="onWhatsappDialogCancel($event)"` on `#whatsappConnectDialogRef` calling
  `event.preventDefault()` when `connectingWhatsapp()` is true. Deliberately scoped to only this dialog — the
  disconnect confirmation dialog has the same latent gap but wasn't in the ticket's scope, left untouched.

Verification for this round: `npm run lint` (0 errors), `npm run format:check` (clean after one
`prettier --write` pass — Prettier is very particular about multi-line Angular template attribute bindings, e.g.
`[class.form-field--error]="…multi-condition…"` and `| transloco: { max: … }` inside `{{ }}` — always run Prettier
after hand-writing these rather than trying to match its wrapping by eye), `npm test` (205/205, no new spec file
per explicit instruction — deferred to a separate task), `npm run build` (clean, confirms SCSS/template compile —
this component still has no `.spec.ts`, so `npm test` alone doesn't exercise its template).
