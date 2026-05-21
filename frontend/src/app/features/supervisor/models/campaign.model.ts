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
  callerId?: string | null;
  dispositionCodes: DispositionCode[];
  maxAttempts: number;
  retryDelayMinutes: number;
  ringTimeoutSeconds: number;
  createdBy?: string;
  createdAt: string;
  updatedAt?: string;
  allAgents?: boolean;
  assignedAgentsCount?: number; // -1 = all agents mode
  totalRecords?: number;
  completedRecords?: number;
  remainingRecords?: number;
}

export interface CreateCampaignRequest {
  name: string;
  type: CampaignType;
  dialerType: DialerType;
  maxAttempts: number;
  retryDelayMinutes: number;
  ringTimeoutSeconds: number;
  schedule?: CampaignSchedule;
  callerId?: string | null;
}

export interface UpdateCampaignRequest {
  name?: string;
  dialerType?: DialerType;
  maxAttempts?: number;
  retryDelayMinutes?: number;
  ringTimeoutSeconds?: number;
  schedule?: CampaignSchedule;
  callerId?: string | null;
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

export type ImportJobStatusType = 'QUEUED' | 'PROCESSING' | 'COMPLETED' | 'FAILED_PARTIAL';

export interface ImportJobStatus {
  jobId: string;
  campaignId: string;
  status: ImportJobStatusType;
  totalRows: number;
  processedRows: number;
  importedRows: number;
  rejectedRows: number;
  rejectedSamples: string[];
  startedAt: string;
  completedAt: string | null;
}

export interface ImportJobStartResponse {
  jobId: string;
  campaignId: string;
  status: ImportJobStatusType;
}

export type CampaignContactStatus =
  | 'PENDING'
  | 'DIALING'
  | 'CONNECTED'
  | 'COMPLETED'
  | 'NO_ANSWER'
  | 'NOT_REACHED'
  | 'CALLBACK'
  | 'FAILED'
  | 'SKIPPED'
  | 'ERROR'
  | 'CALLED'; // backwards compatibility

export interface ManualCallResponse {
  callId: string;
  recordId: string;
  status: 'DIALING';
}

export interface CampaignContact {
  recordId: string;
  phone: string;
  firstName: string | null;
  lastName: string | null;
  customFields: Record<string, string> | null;
  status: CampaignContactStatus;
  dispositionCode: string | null;
  createdAt: string;
  attemptCount: number;
  nextAttemptAt: string | null;
  lastContactId?: string | null;
}

export interface ContactAttempt {
  contactId: string;
  startedAt: string;
  endedAt: string | null;
  durationSeconds: number | null;
  status: string;
  dispositionCode: string | null;
  agentId: string | null;
}

export interface ManualCampaignRecord {
  recordId: string;
  phone: string;
  firstName: string | null;
  lastName: string | null;
  status: string;
}

export interface ManualCampaignGroup {
  campaignId: string;
  campaignName: string;
  records: ManualCampaignRecord[];
}

export interface AgentSummary {
  agentId: string;
  firstName: string;
  lastName: string;
  email: string;
}

export interface CampaignAgentGroupSummary {
  groupId: string;
  name: string;
  memberCount: number;
}

export interface CampaignAssignment {
  campaignId: string;
  allAgents: boolean;
  directAgents: AgentSummary[];
  groups: CampaignAgentGroupSummary[];
}

export interface UpdateCampaignAssignmentRequest {
  allAgents: boolean;
  directAgentIds: string[];
  groupIds: string[];
}
