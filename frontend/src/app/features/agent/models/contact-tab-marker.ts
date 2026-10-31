import { CallSession } from './call-session.model';
import { ContactTab } from './contact-tab.model';

/** The one state marker a tab shows (FE-133), highest priority first. */
export type TabMarker = 'ACW' | 'HOLD' | 'CALL' | 'DRAFT';

/**
 * True when the softphone session is the call of this PHONE tab. Matches the tab's current
 * `contactId` and, for an attended-transfer consultation tab, its `originalContactId`, so the
 * match survives the bridge that swaps the Twilio SID for the contact UUID.
 */
export function sessionMatchesTab(session: CallSession | null, tab: ContactTab): boolean {
  if (!session || tab.type !== 'PHONE') return false;
  return (
    session.contactId === tab.contactId ||
    (tab.originalContactId !== undefined && session.contactId === tab.originalContactId)
  );
}

/**
 * Picks the single marker of a tab: ACW > Hold > In call > Draft.
 *
 * Single sources of truth (no copy of a state lives in two places):
 * - ACW: the tab's own `WRAPPING` status (set by the call lifecycle).
 * - Hold / In call: the SoftphoneService session of this tab's call, never `tab.status`
 *   (`HOLD` on the tab is not maintained; a ringing tab is `ACTIVE` too but is not "in call").
 * - Draft: ContactDraftStore.
 */
export function resolveTabMarker(
  tab: ContactTab,
  session: CallSession | null,
  hasUnsentDraft: boolean,
): TabMarker | null {
  if (tab.status === 'WRAPPING') return 'ACW';
  if (session && sessionMatchesTab(session, tab)) {
    if (session.state === 'ON_HOLD') return 'HOLD';
    if (session.state === 'ACTIVE' || session.state === 'TRANSFERRING') return 'CALL';
  }
  return hasUnsentDraft ? 'DRAFT' : null;
}
