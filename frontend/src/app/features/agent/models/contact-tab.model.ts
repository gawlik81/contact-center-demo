export type ContactType = 'PHONE' | 'CHAT' | 'EMAIL' | 'SOCIAL';
export type ContactTabStatus = 'ACTIVE' | 'HOLD' | 'WRAPPING';
export type CallDirection = 'INBOUND' | 'OUTBOUND';

/** Extended tab data used when the tab enters ACW (After Contact Work) state */
export interface WrappingContactTab extends ContactTab {
  status: 'WRAPPING';
}

/**
 * Key of every per-contact ACW draft (disposition form, PHONE note). One function, used by the
 * desktop and the form, so it stays in line with `ContactDraftStore.rekey` (keyed by `contactId`).
 */
export function acwDraftKey(tab: Pick<ContactTab, 'contactId'>): string {
  return tab.contactId;
}

export interface ContactTab {
  id: string;
  type: ContactType;
  contactId: string;
  customerName: string;
  customerIdentifier: string; // phone number or email
  status: ContactTabStatus;
  isActive: boolean;
  startedAt: Date;
  /** Only set for PHONE tabs; undefined for CHAT/EMAIL */
  direction?: CallDirection;
  /** Customer UUID – propagated from ContactAssignedPayload when available */
  customerId?: string;
  // PHONE note lives in ContactDraftStore (keyed by contactId), not on the tab.
  /**
   * For attended-transfer consultation tabs: the original contact UUID (for disposition and history APIs).
   * contactId holds the Twilio second-leg SID (needed for answerCall/hangupCall).
   * undefined for regular tabs where contactId IS the contact UUID.
   */
  originalContactId?: string;
  /** Epoch ms when the tab entered WRAPPING (ACW); the ACW timer is derived from it. */
  wrappingSince?: number;
}
