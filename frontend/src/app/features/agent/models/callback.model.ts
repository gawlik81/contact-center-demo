export interface RescheduleCallbackRequest {
  scheduledAt: string; // ISO 8601
  notes?: string;
}

export interface CreateInboundCallbackRequest {
  phone: string;
  firstName?: string;
  lastName?: string;
  scheduledAt: string; // ISO 8601
  notes?: string;
}

export interface ScheduledCallbackDto {
  callbackId: string;
  phone: string;
  firstName?: string;
  lastName?: string;
  scheduledAt: string;
  notes?: string;
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'CANCELLED';
  sourceType: 'CAMPAIGN_CALLBACK' | 'INBOUND_CALLBACK' | 'OUTBOUND_CALLBACK';
  agentId?: string;
}
