export interface ManualCallbackRequest {
  customerId: string;
  phoneNumber: string;
  scheduledAt: string; // ISO 8601
  notes?: string;
}

export interface ManualCallbackResponse {
  callbackId: string;
  customerName: string;
  phoneNumber: string;
  scheduledAt: string; // ISO 8601
  status: 'PENDING';
}
