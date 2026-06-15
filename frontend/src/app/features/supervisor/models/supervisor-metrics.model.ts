export type AgentStatus = 'AVAILABLE' | 'BUSY' | 'BREAK' | 'AFTER_CONTACT' | 'OFFLINE';

export interface AgentMetric {
  id: string;
  name: string;
  status: AgentStatus;
  currentContact: string | null;
  breakStartedAt: string | null; // ISO 8601, null when not on break
}

export interface QueueMetric {
  id: string;
  name: string;
  waiting: number;
  availableAgents: number;
}

export interface KpiMetric {
  activeCalls: number;
  callsInIvr: number;
  avgWaitTime: number; // seconds
  avgHandleTime: number; // seconds
}

export interface SupervisorMetrics {
  agents: AgentMetric[];
  queues: QueueMetric[];
  kpi: KpiMetric;
}

/** Raw snake_case payload received from WebSocket backend */
export interface SupervisorMetricsRawPayload {
  agents: {
    id: string;
    name: string;
    status: AgentStatus;
    current_contact: string | null;
    break_started_at: string | null;
  }[];
  queues: {
    id: string;
    name: string;
    waiting: number;
    available_agents: number;
  }[];
  kpi: {
    active_calls: number;
    calls_in_ivr: number;
    avg_wait_time: number;
    avg_handle_time: number;
  };
}
