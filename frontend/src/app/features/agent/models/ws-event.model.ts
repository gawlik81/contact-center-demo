import { AgentStatus } from './agent-status.model';
import { ContactType } from './contact-tab.model';
import { QueueItem } from './queue-item.model';

export type WsEventType =
  | 'CALL_INCOMING'
  | 'CALL_OUTBOUND'
  | 'CALL_HANGUP'
  | 'AGENT_STATUS_CHANGED'
  | 'CONTACT_ASSIGNED'
  | 'QUEUE_UPDATE'
  | 'SUPERVISOR_METRICS'
  | 'PONG';

export interface WsEvent {
  eventType: WsEventType;
  payload: unknown;
}

export interface CallIncomingPayload {
  contactId: string;
  customerName: string;
  customerPhone: string;
  queueName: string;
}

/**
 * Payload dla eventu CALL_OUTBOUND (wychodzące połączenie kampanijne).
 *
 * Struktura identyczna z CallIncomingPayload — backend używa tego samego
 * CallIncomingPayload.from(callEvent). Pole customerPhone zawiera numer klienta
 * (pole `to` z CallEvent), queueName jest puste lub zawiera nazwę kampanii.
 */
export interface CallOutboundPayload {
  contactId: string;
  customerName: string;
  customerPhone: string;
  queueName: string;
}

export interface ContactAssignedPayload {
  contactId: string;
  type: ContactType;
  customerName: string;
  customerIdentifier: string;
  queueName?: string;
}

export interface AgentStatusChangedPayload {
  agentId: string;
  status: AgentStatus;
}

export interface QueueUpdatePayload {
  items: QueueItem[];
}
