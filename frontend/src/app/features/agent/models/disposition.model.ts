export interface DispositionCode {
  code: string;
  label: string;
}

export const DISPOSITION_CODES: DispositionCode[] = [
  { code: 'SALE', label: 'Sprzedaz' },
  { code: 'NO_INTEREST', label: 'Brak zainteresowania' },
  { code: 'CALLBACK', label: 'Oddzwonienie' },
  { code: 'WRONG_NUMBER', label: 'Bledny numer' },
  { code: 'TECH_ISSUE', label: 'Zgloszenie techniczne' },
  { code: 'OTHER', label: 'Inne' },
];
