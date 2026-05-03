export interface GdprConsent {
  consent_given: boolean;
  consent_date?: string;
  consent_source?: string;
  marketing_consent?: boolean;
  data_processing_consent?: boolean;
}

export type CustomerSource = 'MANUAL' | 'CSV_IMPORT' | 'INBOUND_PHONE' | 'AUTO' | string;

export interface CustomerResponse {
  customerId: string;
  tenantId: string;
  firstName?: string;
  lastName?: string;
  phone: string[];
  email: string[];
  customFields: Record<string, unknown>;
  gdprConsent: GdprConsent;
  source: CustomerSource;
  isDeleted: boolean;
  createdAt: string;
  updatedAt?: string;
}

export interface CustomerListParams {
  q?: string;
  page: number;
  size: number;
  sort?: string;
}

export interface PagedResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}
