import { ContactType } from './contact-tab.model';

/** Derived by the backend (BE-157): where in its life the contact is for this agent. */
export type ContactPhase = 'RINGING' | 'IN_PROGRESS' | 'WRAP_UP';

/** Element of `GET /api/agent/me/active-contacts` (instants are ISO-8601 strings). */
export interface ActiveContactResponse {
  contactId: string;
  /** PHONE / EMAIL / SOCIAL (SOCIAL_* family collapsed by the backend); CHAT only from old data. */
  channel: string;
  direction?: string | null;
  /** Raw status: ASSIGNED / ACTIVE / ON_HOLD / COMPLETED. */
  status: string;
  phase: ContactPhase;
  assignedAt?: string | null;
  startedAt?: string | null;
  endedAt?: string | null;
  /** Only for WRAP_UP: the moment ACW began (equals endedAt). */
  wrapUpStartedAt?: string | null;
  customerId?: string | null;
  customerIdentifier: string;
  customerName?: string | null;
  queueId?: string | null;
  queueName?: string | null;
  subject?: string | null;
}

/** Why a contact did not get a card on recovery. */
export type RecoveryOverflowReason =
  | 'MAX_PHONE'
  | 'MAX_TOTAL'
  /** A PHONE contact while another call is live in this browser. */
  | 'OTHER_CALL_ACTIVE'
  /** A channel this desktop cannot show. */
  | 'UNSUPPORTED_CHANNEL';

/** A contact the agent still owns but that has no card because of the tab limits. */
export interface OverflowContact {
  contactId: string;
  /** null for a channel the desktop does not know. */
  type: ContactType | null;
  customerName: string;
  phase: ContactPhase;
  reason: RecoveryOverflowReason;
  /** Needed to open a card from the list once a slot frees up (absent in old entries). */
  customerIdentifier?: string;
  customerId?: string;
  queueName?: string;
  queueId?: string;
  /** Epoch ms of the assignment: the card opened from the list keeps this clock, not "now". */
  assignedAt?: number;
}

/** Outcome of one recovery run, for the UI that reports it (null source = automatic run). */
export interface RecoverySummary {
  id: number;
  /** False when the answer could not be loaded (error / timeout): nothing changed. */
  ok: boolean;
  /** True when only the old endpoint answered: the picture is incomplete, so not a real result. */
  degraded: boolean;
  /** Cards opened or turned into ACW by this run. */
  opened: number;
  /** Contacts still without a card. */
  remaining: number;
  /** PHONE cards currently shown without a live call. */
  callNotRestored: number;
  source: 'overflow' | 'card' | null;
}
