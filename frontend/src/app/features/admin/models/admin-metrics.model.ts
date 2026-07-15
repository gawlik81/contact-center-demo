export interface TenantMetricsSummary {
  id: string;
  name: string;
  status: 'ACTIVE' | 'INACTIVE' | 'SUSPENDED';
  agentsOnline: number;
  agentsTotal: number;
  contactsToday: number;
  avgHandleTimeSeconds: number;
  fcrPercentage: number;
  /** agentsTotal / tenant.maxAgents * 100 — NOT the same as agentsOnline/agentsTotal. */
  agentLimitUtilizationPercent: number;
}

export interface GlobalMetrics {
  totalActiveTenants: number;
  totalAgentsOnline: number;
  totalActiveContacts: number;
  systemAlerts: string[];
  tenants: TenantMetricsSummary[];
  generatedAt: string;
}

export interface TenantMetricsDetail {
  id: string;
  name: string;
  status: 'ACTIVE' | 'INACTIVE' | 'SUSPENDED';
  agentsOnline: number;
  agentsTotal: number;
  activeContacts: number;
}

export interface UsageMetrics {
  contactsHandledToday: number;
  contactsHandledDeltaPercent: number | null;
  avgHandleTimeSeconds: number;
  avgWaitTimeSeconds: number;
  fcrPercentage: number;
  campaignsRunning: number;
  campaignsTotal: number;
  generatedAt: string;
}

export interface WeeklyGrowthPoint {
  weekStart: string;
  newTenants: number;
  newUsers: number;
}

export interface TopPlugin {
  pluginName: string;
  installCount: number;
}

export interface GrowthMetrics {
  weeklyPoints: WeeklyGrowthPoint[];
  topPlugins: TopPlugin[];
  generatedAt: string;
}

export interface QueueDepth {
  queueName: string;
  messageCount: number;
}

export interface SystemResourceMetrics {
  cpuUsagePercent: number;
  heapUsedBytes: number;
  heapMaxBytes: number;
  threadsLive: number;
  uptimeSeconds: number;
  dbPoolActive: number;
  dbPoolMax: number;
  redisStatus: 'UP' | 'DOWN';
  redisAgentSessions: number;
  rabbitStatus: 'UP' | 'DOWN';
  queueDepths: QueueDepth[];
  generatedAt: string;
}

/** Mirrors backend com.contactcenter.domain.etl.EtlTableStatus record. */
export interface EtlTableStatus {
  tableName: string;
  lastSyncedAt: string | null;
  lastRunAt: string | null;
  lastRowCount: number;
  status: 'IDLE' | 'RUNNING' | 'DONE' | 'ERROR';
  lagMinutes: number | null;
  errorMessage: string | null;
}

/** One row of the contacts-by-channel matrix — a single tenant's counts across all channels. */
export interface TenantChannelRow {
  tenantId: string;
  tenantName: string;
  /** Key = channel name (see {@link ContactChannelMatrix.channels}); value = contact count in the selected range. */
  countsByChannel: Record<string, number>;
  /** Sum across all channels for the selected range. */
  total: number;
}

/** Contact counts broken down by tenant and channel for a selected day range (7/30/90 days). */
export interface ContactChannelMatrix {
  /** Fixed column order: PHONE, EMAIL, SOCIAL_FACEBOOK, SOCIAL_INSTAGRAM, SOCIAL_WHATSAPP. */
  channels: string[];
  tenants: TenantChannelRow[];
  /** ISO LocalDate (e.g. "2026-06-16") — start of the requested range. */
  fromDate: string;
  /** ISO LocalDate (e.g. "2026-07-15") — end of the requested range (today). */
  toDate: string;
  generatedAt: string;
}
