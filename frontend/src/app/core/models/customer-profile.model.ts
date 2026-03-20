export interface CustomerProfile {
  id: string;
  firstName: string;
  lastName: string;
  phones: string[];
  emails: string[];
  recentContacts: ContactHistoryItem[];
}

export interface ContactHistoryItem {
  id: string;
  channel: 'PHONE' | 'EMAIL' | 'CHAT' | 'SOCIAL';
  date: string; // ISO 8601
  disposition: string;
  agentName?: string;
}
