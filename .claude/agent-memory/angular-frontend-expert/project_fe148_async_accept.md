---
name: project-fe148-async-accept
description: FE-148 - AsyncContactAcceptService is the single sender of POST /contacts/{id}/accept for EMAIL/SOCIAL cards (live path, card mount, recovery)
metadata:
  type: project
---

`features/agent/services/async-contact-accept.service.ts`: `accept(contactId)` = `ContactDraftStore.markAccepted` guard + `ContactService.acceptContact`, `unmarkAccepted` on error, silent (no toast, same as old email behavior). Callers: `IncomingCallAlertService.handleAsyncContactAssigned` (after the tab opens, only EMAIL/SOCIAL, so a never-mounted background card is still accepted), `email-contact` / `social-contact` ngOnInit, `AgentRecoveryService.openCard` (RINGING non-PHONE). Recovery IN_PROGRESS still only calls `drafts.markAccepted`.

**Why:** social card never accepted on the live path; ContactAssignmentMonitor re-queues ASSIGNED contacts after 3 retries.
**How to apply:** any new async channel card must call the service, never POST accept itself. In specs that emit PHONE CONTACT_ASSIGNED mock `CustomerLookupService.lookupByPhone` to return an observable.
