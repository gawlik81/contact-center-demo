export interface ContactResponse {
  contactId: string;
  tenantId: string;
  customerId: string | null;
  agentId: string;
  queueId?: string;
  campaignId?: string;
  channel: 'PHONE' | 'EMAIL' | 'CHAT' | 'SOCIAL';
  status: 'QUEUED' | 'ACTIVE' | 'ON_HOLD' | 'COMPLETED' | 'ABANDONED' | 'FAILED' | string;
  direction: 'INBOUND' | 'OUTBOUND' | string;
  remoteAddress?: string;
  startedAt: string;
  answeredAt?: string;
  endedAt: string | null;
  durationSeconds?: number;
  dispositionCode: string | null;
  notes?: string | null;
  recordingUrl?: string | null;
}

export interface SetDispositionRequest {
  dispositionCode: string;
  notes: string;
}

export interface RecordingUrlResponse {
  presignedUrl: string;
  expiresAt: string;
  fileName: string;
  durationSeconds?: number;
}
