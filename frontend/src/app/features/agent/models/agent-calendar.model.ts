export type CallbackSourceType = 'CAMPAIGN_CALLBACK' | 'INBOUND_CALLBACK' | 'AGENT_MANUAL';
export type BreakType = 'LUNCH' | 'SHORT_BREAK' | 'TRAINING' | 'MEETING' | 'OTHER';
export type BreakStatus = 'PLANNED' | 'ACTIVE' | 'COMPLETED' | 'CANCELLED';

export interface CalendarCallback {
  id: string;
  customerName: string;
  scheduledAt: string; // ISO-8601
  sourceType: CallbackSourceType;
  status: string;
}

export interface CalendarCampaign {
  id: string;
  name: string;
  startDate: string | null;
  endDate: string | null;
  status: string;
}

export interface CalendarBreak {
  id: string;
  startTime: string; // ISO-8601
  endTime: string; // ISO-8601
  breakType: BreakType;
  notes: string | null;
  status: BreakStatus;
}

export interface AgentCalendarResponse {
  callbacks: CalendarCallback[];
  campaigns: CalendarCampaign[];
  breaks: CalendarBreak[];
}

export interface AgentBreakResponse {
  id: string;
  agentId: string;
  startTime: string;
  endTime: string;
  breakType: BreakType;
  notes: string | null;
  status: BreakStatus;
  createdAt: string;
  updatedAt: string;
}

export interface AgentBreakRequest {
  startTime: string; // ISO-8601
  endTime: string; // ISO-8601
  breakType: BreakType;
  notes?: string;
}
