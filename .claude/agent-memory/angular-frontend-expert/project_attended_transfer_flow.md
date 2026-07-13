---
name: project_attended_transfer_flow
description: Attended transfer frontend state machine — Agent1 initiates, Agent2 receives, cancellation flow
metadata:
  type: project
---

# Attended Transfer Frontend Flow

## Agent2 consultation lifecycle

1. **CALL_TRANSFER_CONSULT** arrives → `IncomingCallAlertService.handleCallTransferConsult()`
   - Opens PHONE tab with `contactId = secondLegCallId` (Twilio CA_... SID), `originalContactId = originalContactId`, `customerName = "[Konsultacja] {name}"`
   - Sets softphone session state = RINGING (so Twilio SDK does not reject incoming call)
   - Shows incoming call banner + ringtone

2a. **Agent2 answers → bridge happens** → `CALL_BRIDGE_COMPLETE`
   - `AgentDesktopComponent` updates `session.contactId` and `tab.contactId` to `newContactId` (real UUID)
   - Strips `[Konsultacja] ` prefix from customerName
   - Full ACW/disposition after call ends normally

2b. **Agent1 cancels consultation** → `CALL_CONSULT_CANCELLED`
   - `IncomingCallAlertService.dismissAlert()` — clears banner and audio
   - `AgentDesktopComponent` calls `SoftphoneService.cancelConsultSession()` — sets session=null DIRECTLY (bypassing ENDED state, so `softphoneEndedEffect` does NOT fire)
   - Closes the PHONE tab identified by `callId` or `contactId` match (fallback: any PHONE tab)
   - Shows info toast "Konsultacja anulowana przez agenta inicjującego"
   - Backend already set Agent2 status to AVAILABLE — frontend must NOT call status API

## Critical guard: how to bypass ACW
- `softphoneEndedEffect` in AgentDesktopComponent fires when `sessionState() === 'ENDED'`
- It calls `changeStatus('AFTER_CONTACT')` and marks PHONE tab as WRAPPING
- To avoid ACW: use `cancelConsultSession()` which sets `session.set(null)` directly — never sets state to ENDED

## PHONE tab identification for consultation
- Tab opened by CALL_TRANSFER_CONSULT has `contactId = secondLegCallId` (CA_...) and `originalContactId` set
- When matching CALL_CONSULT_CANCELLED payload: check `t.contactId === payload.callId || t.contactId === payload.contactId || t.originalContactId !== undefined`
