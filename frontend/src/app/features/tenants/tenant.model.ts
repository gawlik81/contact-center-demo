export type TenantStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED';

export interface TenantConfig {
  max_agents: number;
  max_queues: number;
  max_campaigns: number;
  recording_retention_days?: number;
  timezone?: string;
  twilio_phone_number?: string;
  twilio_status_callback_url?: string;
}

export interface TenantLimits {
  max_agents: number;
  max_queues: number;
  max_campaigns: number;
  recording_retention_days: number;
  timezone: string;
}

export interface Tenant {
  id: string;
  name: string;
  status: TenantStatus;
  config: TenantConfig;
  limits?: TenantLimits;
  createdAt: string;
  updatedAt?: string | null;
}

export interface CreateTenantRequest {
  name: string;
  limits?: {
    max_agents?: number;
    max_queues?: number;
    max_campaigns?: number;
    recording_retention_days?: number;
    timezone?: string;
  };
}

export interface UpdateTenantRequest {
  name?: string | null;
  status?: TenantStatus | null;
  limits?: {
    max_agents?: number | null;
    max_queues?: number | null;
    max_campaigns?: number | null;
  } | null;
}

export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export interface NameAvailabilityResponse {
  available: boolean;
}

export interface TenantListParams {
  name?: string;
  status?: TenantStatus | '';
  page: number;
  size: number;
}
