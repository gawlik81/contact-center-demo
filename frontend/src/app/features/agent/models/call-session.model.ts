export type CallState = 'RINGING' | 'ACTIVE' | 'ON_HOLD' | 'TRANSFERRING' | 'ENDED';

export type TransferTargetType = 'PHONE' | 'AGENT' | 'QUEUE';

export type TransferMode = 'BLIND' | 'ATTENDED';

export interface TransferAgentItem {
  agentId: string;
  firstName: string;
  lastName: string;
  status: 'AVAILABLE' | 'BUSY' | 'BREAK' | 'ON_CALL';
  queueNames: string[];
}

export interface TransferQueueItem {
  queueId: string;
  name: string;
  waitingContacts: number;
  availableAgents: number;
}

export interface CallSession {
  contactId: string;
  customerName: string;
  customerPhone: string;
  queueName: string;
  state: CallState;
  startedAt: Date | null;
  duration: number;
  isMuted: boolean;
  holdStartedAt: Date | null;
  holdDuration: number;
  transferTarget: string | null;
}
