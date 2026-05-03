export type AgentStatus = 'AVAILABLE' | 'BUSY' | 'BREAK' | 'AFTER_CONTACT' | 'OFFLINE';

export interface AgentStatusConfig {
  /** @deprecated Use labelKey with transloco pipe instead */
  label: string;
  /** Translation key for transloco pipe */
  labelKey: string;
  color: string;
  bgColor: string;
  icon: string;
}

export const AGENT_STATUS_CONFIG: Record<AgentStatus, AgentStatusConfig> = {
  AVAILABLE: {
    label: 'Dostepny',
    labelKey: 'agent.status.available',
    color: '#2e7d32',
    bgColor: '#e8f5e9',
    icon: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 14.5v-9l6 4.5-6 4.5z',
  },
  BUSY: {
    label: 'Zajety',
    labelKey: 'agent.status.busy',
    color: '#c62828',
    bgColor: '#ffebee',
    icon: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm4 14H8v-2h8v2zm0-4H8v-2h8v2zm0-4H8V6h8v2z',
  },
  BREAK: {
    label: 'Przerwa',
    labelKey: 'agent.status.break',
    color: '#f57f17',
    bgColor: '#fffde7',
    icon: 'M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67V7z',
  },
  AFTER_CONTACT: {
    label: 'Po kontakcie',
    labelKey: 'agent.status.afterContact',
    color: '#1565c0',
    bgColor: '#e3f2fd',
    icon: 'M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z',
  },
  OFFLINE: {
    label: 'Offline',
    labelKey: 'agent.status.offline',
    color: '#616161',
    bgColor: '#f5f5f5',
    icon: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm-1-13h2v6h-2zm0 8h2v2h-2z',
  },
};

export const ALL_AGENT_STATUSES: AgentStatus[] = [
  'AVAILABLE',
  'BUSY',
  'BREAK',
  'AFTER_CONTACT',
  'OFFLINE',
];
