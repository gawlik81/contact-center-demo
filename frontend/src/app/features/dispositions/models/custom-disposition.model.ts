export type DispositionToneApi = 'positive' | 'negative' | 'neutral' | 'warning';

export interface AvailableDisposition {
  dispositionCode: string;
  label: string;
  tone: DispositionToneApi;
  ordinal: number;
}

export interface CustomDisposition extends AvailableDisposition {
  id: string;
  isActive: boolean;
  createdAt: string;
}

export interface CreateCustomDispositionRequest {
  dispositionCode: string;
  label: string;
  tone: DispositionToneApi;
  ordinal: number;
}

export interface UpdateCustomDispositionRequest {
  label: string;
  tone: DispositionToneApi;
  ordinal: number;
  isActive: boolean;
}

export const TONE_CSS_CLASS: Record<DispositionToneApi, string> = {
  positive: 'tone-positive',
  negative: 'tone-negative',
  warning: 'tone-warning',
  neutral: 'tone-neutral',
};
