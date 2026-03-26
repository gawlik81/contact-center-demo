---
name: Email contact component (FE-012)
description: EmailContactComponent — split-panel thread viewer + reply editor for Agent Desktop EMAIL tabs
type: project
---

Implemented FE-012 on 2026-03-25.

**Files created:**
- `frontend/src/app/features/agent/services/email.service.ts` — HTTP service (getMessage, getThread, sendReply, getTemplates, previewTemplate)
- `frontend/src/app/features/agent/.../email-contact/email-contact.component.{ts,html,scss}` — main component
- `frontend/src/app/features/agent/.../email-contact/email-thread-message/email-thread-message.component.{ts,html,scss}` — single message in thread
- `frontend/src/app/features/agent/.../email-contact/email-contact.component.spec.ts` — 16 unit tests

**Files modified:**
- `agent-desktop.component.ts` — added EmailContactComponent import + `onEmailReplySent()` handler
- `agent-desktop.component.html` — replaced EMAIL placeholder with `<cc-email-contact>` binding

**Key design decisions:**
- Split panel: 40% thread (scrollable, INBOUND left / OUTBOUND right), 60% reply editor
- `<iframe srcdoc>` for XSS-safe HTML rendering with auto-height via `(load)` event
- Contenteditable div as rich text editor (Bold/Italic/Underline/Link via `document.execCommand`)
- Custom autocomplete dropdown for template selection (no Angular Material)
- Template variables shown inline as form fields when template has `variables[]`
- `input()`, `output()`, `signal()`, `computed()`, `OnPush` — full Angular 21 patterns

**Why:** Angular Material is not installed in this project; custom CSS patterns match existing components.
