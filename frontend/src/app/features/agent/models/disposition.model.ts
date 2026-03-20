export interface DispositionCode {
  code: string;
  label: string;
}

export const DISPOSITION_CODES: DispositionCode[] = [
  { code: 'SALE', label: 'Sprzedaż' },
  { code: 'NO_INTEREST', label: 'Brak zainteresowania' },
  { code: 'CALLBACK', label: 'Oddzwonienie' },
  { code: 'WRONG_NUMBER', label: 'Błędny numer' },
  { code: 'TECH_ISSUE', label: 'Zgłoszenie techniczne' },
  { code: 'OTHER', label: 'Inne' },
];
