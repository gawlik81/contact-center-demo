---
name: project-fe136-recovery
description: FE-136 agent-recovery.service rebuilt on GET /agent/me/active-contacts - phases, limit planning, overflow notice, re-login, PHONE IN_PROGRESS decision
metadata:
  type: project
---

`AgentRecoveryService` (features/agent/services) now restores all cards from ONE `active-contacts` call (BE-157); 404 -> `assigned-contact` fallback, error/10 s timeout -> desktop untouched (SKIP_ERROR_TOAST).

- RINGING PHONE -> softphone.incomingCall; RINGING e-mail/social -> card + accept once (ContactDraftStore.markAccepted guards the card's own mount-accept). IN_PROGRESS -> card + markAccepted only (no accept, FE-131). WRAP_UP (PHONE) -> tab status WRAPPING with `wrappingSince = wrapUpStartedAt`.
- PHONE IN_PROGRESS: WebRTC leg dies with the page, so the tab gets `callNotRestored: true` and softphone idle branch shows an explanation (backend disposition PATCH needs ON_HOLD/COMPLETED, ACTIVE gives 409, so no ACW shortcut). Reload after call end -> WRAP_UP. An already-open callNotRestored tab becomes WRAPPING if a later run reports WRAP_UP.
- Limits: `tabLimitReason()` exported from contact-tab.store (single source). Slots are planned open-first/ACW-last, cards opened in backend order; the rest goes to `service.overflow` signal, shown by `cc-recovery-overflow-notice` (in desktop after app-telephony-error) with "check again" (re-runs recovery). SOCIAL counts only toward MAX_TOTAL (asyncTabCount counts CHAT/EMAIL).
- Re-login finding: AgentShellComponent is the /agent route, destroyed on logout and recreated on login -> new ws.connect -> onConnect -> recovery runs. Added `recovery.reset()` in shell ngOnDestroy (generation counter drops an answer of the previous session).
- Plural title keys `agent.recoveryOverflow.title_{one,few,many,other}` via Intl.PluralRules + pipe (copied from queue-panel).
- impeccable detect (regex-only for scss/ts) flags pre-existing `Geist Mono` at softphone.component.scss:306, not ours; html templates are not scanned.

## Round 2 (CR fixes, 2026-10-31)
- `callNotRestored` card has its own "Sprawdz ponownie" (`checkAgain('card')`) and the service polls every 20 s only while such a card exists (effect in service constructor; stops with the tab/logout).
- Slot priority: PHONE (open before ACW) first, then others in backend order. `closeMark()/closedSince()` in ContactTabStore drops contacts closed while the request was in flight (stale snapshot). Fallback to assigned-contact on 404 OR 500 (catch-all handler turns unknown path into 500). Legacy answer is `complete:false` and never erases overflow.
- ACW entering from existing card uses `markAsWrapping(id, since)`; recovery moves AVAILABLE agent to AFTER_CONTACT (needs AgentStatusService mock in specs). Overflow reasons OTHER_CALL_ACTIVE/UNSUPPORTED_CHANNEL; `lastRun` summary drives announcements; notice moves focus to the selected tab when emptied; auto refresh after a card closes (debounced).
- SOCIAL RINGING: recovery keeps `accept` (live path never accepts SOCIAL; backend accept is channel-agnostic) - live-path gap reported as follow-up.

## Round 3 (2026-10-31)
- Poll effect reads only computed `hasNotRestored`. `dismissNotRestored(contactId)` (called by desktop requestClose for STALE callNotRestored tab; label key `closeNotRestored`) hides the card while IN_PROGRESS, releases at WRAP_UP; cleared in reset().
- RecoverySummary.degraded (legacy answer) is treated as failure in messages; overflow result cleared while loading; focus handed to the selected tab by an effect when the list empties with focus inside.
- AFTER_CONTACT only if status AVAILABLE and `statusSince` unchanged since run start; `changeStatus(status, {silent:true})` skips toasts (also SKIP_ERROR_TOAST). Store reset() clears closedLog/closeSeq. "20 s" is `{{seconds}}` param.
- Round 4: dismissed set mirrored in signal dismissedCount; needsPolling = hasNotRestored || dismissedCount>0; entries dropped when a complete answer omits them.
