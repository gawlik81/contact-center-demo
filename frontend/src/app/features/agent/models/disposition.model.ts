export type DispositionTone = 'accent' | 'success' | 'warning' | 'danger' | 'violet' | 'neutral';

export interface DispositionCode {
  code: string;
  labelKey: string;
  tone: DispositionTone;
}

export const DISPOSITION_CODES: DispositionCode[] = [
  { code: 'SALE', labelKey: 'agent.dispositionCodes.SALE', tone: 'violet' },
  { code: 'NO_INTEREST', labelKey: 'agent.dispositionCodes.NO_INTEREST', tone: 'warning' },
  { code: 'CALLBACK', labelKey: 'agent.dispositionCodes.CALLBACK', tone: 'accent' },
  { code: 'WRONG_NUMBER', labelKey: 'agent.dispositionCodes.WRONG_NUMBER', tone: 'danger' },
  { code: 'TECH_ISSUE', labelKey: 'agent.dispositionCodes.TECH_ISSUE', tone: 'neutral' },
  { code: 'OTHER', labelKey: 'agent.dispositionCodes.OTHER', tone: 'neutral' },
];
