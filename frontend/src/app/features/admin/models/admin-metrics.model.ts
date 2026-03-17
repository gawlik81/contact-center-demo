export interface TenantMetricsSummary {
  id: string;
  name: string;
  status: 'ACTIVE' | 'INACTIVE' | 'SUSPENDED';
  agentsOnline: number;
  agentsTotal: number;
}

export interface GlobalMetrics {
  totalActiveTenants: number;
  totalAgentsOnline: number;
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
  cpuUsage: number;
  memoryUsage: number;
  activeContacts: number;
}
