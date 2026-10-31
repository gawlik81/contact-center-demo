export type AgentStatus = 'AVAILABLE' | 'BUSY' | 'BREAK' | 'AFTER_CONTACT' | 'OFFLINE';

/**
 * Semantic colour family of a status. Maps to the `--<tone>` / `--<tone>-soft` / `--<tone>-text`
 * triad in styles.scss (see `[data-status-tone]` in agent-desktop.component.scss), so statuses
 * follow the active theme. Colour is never the only carrier: label and icon stay on screen.
 */
export type AgentStatusTone = 'success' | 'danger' | 'warning' | 'violet' | 'neutral';

export interface AgentStatusConfig {
  /** Translation key for transloco pipe */
  labelKey: string;
  tone: AgentStatusTone;
  icon: string;
}

export const AGENT_STATUS_CONFIG: Record<AgentStatus, AgentStatusConfig> = {
  AVAILABLE: {
    labelKey: 'agent.status.available',
    tone: 'success',
    icon: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 14.5v-9l6 4.5-6 4.5z',
  },
  BUSY: {
    labelKey: 'agent.status.busy',
    tone: 'danger',
    icon: 'M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm4 14H8v-2h8v2zm0-4H8v-2h8v2zm0-4H8V6h8v2z',
  },
  BREAK: {
    labelKey: 'agent.status.break',
    tone: 'warning',
    icon: 'M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67V7z',
  },
  AFTER_CONTACT: {
    labelKey: 'agent.status.afterContact',
    tone: 'violet',
    icon: 'M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z',
  },
  OFFLINE: {
    labelKey: 'agent.status.offline',
    tone: 'neutral',
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
