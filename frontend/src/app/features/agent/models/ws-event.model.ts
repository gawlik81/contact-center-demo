import { AgentStatus } from './agent-status.model';
import { ContactType } from './contact-tab.model';
import { QueueItem } from './queue-item.model';

export type WsEventType =
  | 'CALL_INCOMING'
  | 'AGENT_STATUS_CHANGED'
  | 'CONTACT_ASSIGNED'
  | 'QUEUE_UPDATE'
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

export interface ContactAssignedPayload {
  contactId: string;
  type: ContactType;
  customerName: string;
  customerIdentifier: string;
}

export interface AgentStatusChangedPayload {
  agentId: string;
  status: AgentStatus;
}

export interface QueueUpdatePayload {
  items: QueueItem[];
}
