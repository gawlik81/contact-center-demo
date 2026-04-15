export interface RescheduleCallbackRequest {
  scheduledAt: string; // ISO 8601
  notes?: string;
}

export interface CallbackListItem {
  callbackId: string;
  phone: string;
  firstName?: string;
  lastName?: string;
  scheduledAt: string; // ISO 8601
  notes?: string;
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'CANCELLED';
  agentId?: string;
  agentName?: string;
}

export interface CallbackListParams {
  status?: 'PENDING' | 'COMPLETED' | 'CANCELLED' | 'PROCESSING' | null;
  sortDir?: 'ASC' | 'DESC';
  page?: number;
  size?: number;
  agentId?: string | null;
}

export interface UpdateCallbackRequest {
  phone?: string;
  firstName?: string;
  lastName?: string;
  scheduledAt?: string;
  notes?: string;
  agentId?: string | null;
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
