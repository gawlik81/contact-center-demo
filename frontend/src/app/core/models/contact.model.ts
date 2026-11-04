export interface ContactResponse {
  contactId: string;
  tenantId: string;
  customerId: string | null;
  agentId: string;
  agentName: string | null;
  queueId?: string;
  campaignId?: string;
  channel: 'PHONE' | 'EMAIL' | 'CHAT' | 'SOCIAL';
  status:
    | 'IVR'
    | 'QUEUED'
    | 'ACTIVE'
    | 'ON_HOLD'
    | 'COMPLETED'
    | 'ABANDONED'
    | 'FAILED'
    | 'TRANSFERRED'
    | string;
  direction: 'INBOUND' | 'OUTBOUND' | string;
  remoteAddress?: string;
  startedAt: string;
  answeredAt?: string;
  endedAt: string | null;
  durationSeconds?: number;
  dispositionCode: string | null;
  dispositionLabel: string | null;
  notes?: string | null;
  recordingUrl?: string | null;
  callbackId?: string | null;
}

export interface RelatedItem {
  itemType: 'CONTACT' | 'CALLBACK';
  relationType: 'PARENT' | 'CHILD';
  // Fields for CONTACT items
  contactId?: string;
  startedAt?: string;
  channel?: string;
  direction?: string;
  status?: string;
  remoteAddress?: string;
  // Fields for CALLBACK items
  callbackId?: string;
  scheduledAt?: string;
  callbackStatus?: string;
  phone?: string;
  firstName?: string;
  lastName?: string;
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
  contentType?: string; // 'audio/mpeg' | 'message/rfc822'
}

export interface EmailPreviewMessage {
  messageId: string;
  from: string;
  to: string;
  cc: string | null;
  subject: string | null;
  bodyHtml: string | null;
  bodyText: string | null;
  /** ISO timestamp; null for OUTBOUND messages. */
  receivedAt: string | null;
  /** ISO timestamp, always present; chronological sort key. */
  messageAt: string;
  direction: 'INBOUND' | 'OUTBOUND';
  attachments: EmailAttachmentInfo[];
  /** True when an EML archive exists for this message. */
  emlAvailable: boolean;
}

export interface EmailPreviewResponse {
  /** Chronological ASC (oldest first). */
  messages: EmailPreviewMessage[];
  totalMessages: number;
  /** True when more messages exist than the returned cap. */
  truncated: boolean;
}

export interface EmailAttachmentInfo {
  filename: string;
  contentType: string;
  sizeBytes: number;
  s3Key: string;
}

export interface ContactEventResponse {
  eventId: string;
  stage: 'IVR' | 'VOICEBOT' | 'QUEUE' | 'AGENT' | 'ON_HOLD' | 'CONSULTING' | 'TRANSFER';
  startedAt: string;
  endedAt: string | null;
  durationSeconds: number | null;
  metadata: Record<string, string>;
}
