---
name: Softphone WebRTC component (FE-010)
description: SoftphoneService + SoftphoneComponent standalone – call state machine, timer, transfer panel (blind/attended), WCAG AA
type: project
---

SoftphoneService (@Injectable providedIn:'root') manages a `signal<CallSession | null>` with full call state machine: RINGING → ACTIVE → ON_HOLD / TRANSFERRING → ENDED → null (2s cleanup). Timer runs via `setInterval` directly in the service (not RxJS interval) because it mutates the signal synchronously; stops on HOLD, resumes on WZNOW.

SoftphoneComponent is standalone, OnPush, imports FormsModule. Receives `@Input({ required: true }) tab: ContactTab`. Reads `softphone.session` signal. Uses a separate `_holdTick` auxiliary signal (incremented by `setInterval` in ngOnInit) to force hold-timer computed() re-evaluation under OnPush.

Transfer panel is a local view inside the ACTIVE state controlled by `_showTransferPanel` signal – it does NOT change CallSession state. Actual state change (ACTIVE → TRANSFERRING) happens only when user submits. Blind transfer auto-completes after 1.5s mock delay. Attended transfer waits for `completeAttendedTransfer()` or `cancelTransfer()`.

AgentDesktopComponent now imports SoftphoneComponent. CALL_INCOMING handler calls `softphoneService.incomingCall(payload)` after opening tab. closeTab() calls `softphoneService.hangupCall()` if tab.type === 'PHONE'.

ContactTabStore gained `updateTabStatus(id, status)` method; import of `ContactTabStatus` added.

**Why:** FE-010 task – mock softphone UI without real SIP/WebRTC; backend integration via MockTelephonyAdapter + WS events already in place.

**How to apply:** When extending softphone (real WebRTC, DTMF), SoftphoneService is the single integration point. SoftphoneComponent is feature-scoped to `features/agent/components/softphone/`.
