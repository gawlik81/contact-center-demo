export interface AgentReportRow {
  agentId: string;
  agentName: string;
  date: string;
  contactsCount: number;
  avgHandleTimeSeconds: number;
  avgWaitTimeSeconds: number;
  firstContactResolution: number;
  channel: string;
}

export interface AgentReportFilters {
  dateFrom: string;
  dateTo: string;
  agentId?: string;
  queueId?: string;
  channel?: string;
}
