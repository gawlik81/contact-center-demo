import { ContactType } from './contact-tab.model';

export interface QueueItem {
  id: string;
  type: ContactType;
  customerName: string;
  customerIdentifier: string; // phone number or email
  waitingSince: Date;
  queueName: string;
  priority: number;
}
