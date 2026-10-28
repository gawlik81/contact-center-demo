export type ContactType = 'PHONE' | 'CHAT' | 'EMAIL' | 'SOCIAL';
export type ContactTabStatus = 'ACTIVE' | 'HOLD' | 'WRAPPING';
export type CallDirection = 'INBOUND' | 'OUTBOUND';

/** Extended tab data used when the tab enters ACW (After Contact Work) state */
export interface WrappingContactTab extends ContactTab {
  status: 'WRAPPING';
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
}
