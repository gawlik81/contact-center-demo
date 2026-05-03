export interface DispositionCode {
  code: string;
  labelKey: string;
}

export const DISPOSITION_CODES: DispositionCode[] = [
  { code: 'SALE', labelKey: 'agent.dispositionCodes.SALE' },
  { code: 'NO_INTEREST', labelKey: 'agent.dispositionCodes.NO_INTEREST' },
  { code: 'CALLBACK', labelKey: 'agent.dispositionCodes.CALLBACK' },
  { code: 'WRONG_NUMBER', labelKey: 'agent.dispositionCodes.WRONG_NUMBER' },
  { code: 'TECH_ISSUE', labelKey: 'agent.dispositionCodes.TECH_ISSUE' },
  { code: 'OTHER', labelKey: 'agent.dispositionCodes.OTHER' },
];
