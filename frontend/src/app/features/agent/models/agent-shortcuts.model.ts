/**
 * Keyboard shortcuts of the Agent surface (handled by AgentHotkeysService).
 * `label` is what the agent reads in tooltips and the cheat sheet; `aria` is the value of
 * `aria-keyshortcuts` (WAI-ARIA key names).
 */
export interface AgentShortcut {
  label: string;
  aria: string;
}

export const AGENT_SHORTCUTS = {
  answer: { label: 'Ctrl+Alt+Enter', aria: 'Control+Alt+Enter' },
  hangup: { label: 'Ctrl+Alt+Backspace', aria: 'Control+Alt+Backspace' },
  mute: { label: 'Ctrl+Alt+M', aria: 'Control+Alt+M' },
  cheatSheet: { label: '?', aria: 'Shift+Slash' },
} as const satisfies Record<string, AgentShortcut>;
