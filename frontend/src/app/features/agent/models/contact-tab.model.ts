export type ContactType = 'PHONE' | 'CHAT' | 'EMAIL';
export type ContactTabStatus = 'ACTIVE' | 'HOLD' | 'WRAPPING';

export interface ContactTab {
  id: string;
  type: ContactType;
  contactId: string;
  customerName: string;
  customerIdentifier: string; // phone number or email
  status: ContactTabStatus;
  isActive: boolean;
  startedAt: Date;
}
