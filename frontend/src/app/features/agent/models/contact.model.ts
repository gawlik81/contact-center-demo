export interface ContactResponse {
  id: string;
  tenantId: string;
  customerId: string | null;
  agentId: string;
  channel: string;
  status: string;
  direction: string;
  startedAt: string;
  endedAt: string | null;
  dispositionCode: string | null;
  notes: string | null;
}

export interface SetDispositionRequest {
  dispositionCode: string;
  notes: string;
}
