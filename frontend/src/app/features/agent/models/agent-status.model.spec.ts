import {
  AGENT_STATUS_CONFIG,
  ALL_AGENT_STATUSES,
  AgentStatus,
  AgentStatusTone,
} from './agent-status.model';

describe('AGENT_STATUS_CONFIG', () => {
  const expectedTone: Record<AgentStatus, AgentStatusTone> = {
    AVAILABLE: 'success',
    BUSY: 'danger',
    BREAK: 'warning',
    AFTER_CONTACT: 'violet',
    OFFLINE: 'neutral',
  };

  it('maps every status to a semantic tone token family', () => {
    for (const status of ALL_AGENT_STATUSES) {
      expect(AGENT_STATUS_CONFIG[status].tone).toBe(expectedTone[status]);
    }
  });

  it('keeps colour literals out of the model', () => {
    const serialised = JSON.stringify(AGENT_STATUS_CONFIG).replace(/"icon":"[^"]*"/g, '');
    expect(serialised).not.toMatch(/#[0-9a-fA-F]{3,8}/);
  });

  it('keeps a label key and an icon for every status (colour is never the only carrier)', () => {
    for (const status of ALL_AGENT_STATUSES) {
      expect(AGENT_STATUS_CONFIG[status].labelKey).toMatch(/^agent\.status\./);
      expect(AGENT_STATUS_CONFIG[status].icon.length).toBeGreaterThan(0);
    }
  });
});
