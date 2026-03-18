export type CallState = 'RINGING' | 'ACTIVE' | 'ON_HOLD' | 'TRANSFERRING' | 'ENDED';

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
