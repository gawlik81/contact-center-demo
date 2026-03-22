export type CampaignStatus = 'DRAFT' | 'SCHEDULED' | 'RUNNING' | 'PAUSED' | 'STOPPED' | 'COMPLETED';
export type CampaignType = 'OUTBOUND_VOICE' | 'OUTBOUND_EMAIL';
export type DialerType = 'PROGRESSIVE' | 'PREDICTIVE' | 'MANUAL';
export type ActiveDay = 'MON' | 'TUE' | 'WED' | 'THU' | 'FRI' | 'SAT' | 'SUN';

export interface CampaignSchedule {
  start_date?: string;
  end_date?: string;
  active_hours?: { from: string; to: string };
  active_days?: ActiveDay[];
  timezone?: string;
}

export interface DispositionCode {
  code: string;
  label: string;
}

export interface Campaign {
  campaignId: string;
  tenantId: string;
  name: string;
  type: CampaignType;
  dialerType: DialerType;
  schedule: CampaignSchedule;
  status: CampaignStatus;
  queueId?: string;
  dispositionCodes: DispositionCode[];
  maxAttempts: number;
  retryDelayMinutes: number;
  createdBy?: string;
  createdAt: string;
  updatedAt?: string;
}

export interface CreateCampaignRequest {
  name: string;
  type: CampaignType;
  dialerType: DialerType;
  maxAttempts: number;
  retryDelayMinutes: number;
  schedule?: CampaignSchedule;
}

export interface UpdateCampaignRequest {
  name?: string;
  dialerType?: DialerType;
  maxAttempts?: number;
  retryDelayMinutes?: number;
  schedule?: CampaignSchedule;
}

export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}
