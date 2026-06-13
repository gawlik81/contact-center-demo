export type IvrNodeType =
  | 'MENU'
  | 'PLAY_AUDIO'
  | 'COLLECT_DTMF'
  | 'QUEUE_TRANSFER'
  | 'HANGUP'
  | 'SET'
  | 'IF'
  | 'SWITCH'
  | 'VOICEBOT';

export interface IvrOption {
  key: string;
  next_node_id: string;
}

export interface IvrNode {
  node_id: string;
  type: IvrNodeType;
  prompt?: string;
  audio_id?: string;
  options?: IvrOption[];
  queue_id?: string;
  timeout_seconds?: number;
  max_retries?: number;
  variable_name?: string;
  min_digits?: number;
  max_digits?: number;
  finish_on_key?: string;
  value?: string;
  operator?: string;
  compare_value?: string;
}

export interface IvrNodeUI extends IvrNode {
  x: number;
  y: number;
}

export interface IvrDefinition {
  nodes: IvrNode[];
  entry_node_id: string;
  layout?: Record<string, { x: number; y: number }>;
}

export interface IvrDefinitionUI {
  nodes: IvrNodeUI[];
  entry_node_id: string;
}

export interface IvrResponse {
  ivr_id: string;
  name: string;
  definition: IvrDefinition;
  version: number;
  is_active: boolean;
  created_at: string;
  updated_at?: string;
}

export interface CreateIvrRequest {
  name: string;
  definition: IvrDefinition;
}

export interface UpdateIvrRequest {
  name?: string;
  definition?: IvrDefinition;
}

export const IVR_NODE_LABELS: Record<IvrNodeType, string> = {
  MENU: 'supervisor.ivrNodeLabels.MENU',
  PLAY_AUDIO: 'supervisor.ivrNodeLabels.PLAY_AUDIO',
  COLLECT_DTMF: 'supervisor.ivrNodeLabels.COLLECT_DTMF',
  QUEUE_TRANSFER: 'supervisor.ivrNodeLabels.QUEUE_TRANSFER',
  HANGUP: 'supervisor.ivrNodeLabels.HANGUP',
  SET: 'supervisor.ivrNodeLabels.SET',
  IF: 'supervisor.ivrNodeLabels.IF',
  SWITCH: 'supervisor.ivrNodeLabels.SWITCH',
  VOICEBOT: 'supervisor.ivrNodeLabels.VOICEBOT',
};

export const IVR_NODE_ICONS: Record<IvrNodeType, string> = {
  MENU: 'M',
  PLAY_AUDIO: 'A',
  COLLECT_DTMF: 'D',
  QUEUE_TRANSFER: 'Q',
  HANGUP: 'H',
  SET: 'S',
  IF: '?',
  SWITCH: '⊞',
  VOICEBOT: 'VB',
};

export const DEMO_IVR: IvrDefinition = {
  entry_node_id: 'node-1',
  nodes: [
    {
      node_id: 'node-1',
      type: 'MENU',
      prompt:
        'Witamy w Contact Center. Nacisnij 1 aby polaczyc z supportem, 2 aby zostawic wiadomosc.',
      timeout_seconds: 10,
      max_retries: 3,
      options: [
        { key: '1', next_node_id: 'node-2' },
        { key: '2', next_node_id: 'node-3' },
        { key: 'timeout', next_node_id: 'node-3' },
      ],
    },
    {
      node_id: 'node-2',
      type: 'QUEUE_TRANSFER',
      queue_id: undefined,
      prompt: 'Lacze z supportem...',
      options: [],
    },
    {
      node_id: 'node-3',
      type: 'HANGUP',
      prompt: 'Dziekujemy za kontakt. Do widzenia.',
      options: [],
    },
  ],
};
