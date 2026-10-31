/**
 * Odpowiedź podglądu anonimizacji klienta (RODO Art. 17, BE-129/FE-112).
 *
 * Kontrakt 1:1 z backendowym rekordem `AnonymizePreviewResponse`
 * (backend/app/src/main/java/com/contactcenter/api/customer/dto/AnonymizePreviewResponse.java) —
 * zwracanym przez `GET /api/customers/{id}/gdpr/anonymize/preview`. Jackson serializuje pola
 * rekordu domyślnie camelCase (brak globalnej strategii snake_case dla tego kontrolera).
 *
 * `counts` to mapa nazwa_tabeli -> liczba rekordów, które zostałyby zmienione/usunięte przy
 * rzeczywistej anonimizacji — klucze pochodzą wprost z funkcji SQL `anonymize_customer` (DB-062,
 * V096__extend_anonymize_customer_gdpr_art17.sql) i NIE są transformowane po stronie FE:
 * `customer`, `contact`, `scheduled_callback`, `campaign_contact`, `campaign_contact_archive`,
 * `contact_transcription`, `contact_ai_summary`, `email_message`, `social_message`, `contacts_dw`.
 */
export interface AnonymizePreviewResponse {
  dryRun: boolean;
  counts: Record<string, number>;
  matchedByLink: number;
  matchedByIdentifier: number;
  s3ObjectsToDelete: number;
}

/**
 * Kolejność wyświetlania znanych kluczy {@link AnonymizePreviewResponse.counts} w modalu
 * anonimizacji (FE-112). Klucze spoza tej listy (przyszłe rozszerzenia backendu) trafiają na
 * koniec, posortowane alfabetycznie — UI nie zakłada zamkniętego zbioru kluczy.
 */
export const GDPR_PREVIEW_COUNT_ORDER: readonly string[] = [
  'contact',
  'email_message',
  'social_message',
  'scheduled_callback',
  'campaign_contact',
  'campaign_contact_archive',
  'contact_transcription',
  'contact_ai_summary',
  'contacts_dw',
  'customer',
];

/**
 * Mapowanie klucza `counts` na klucz i18n etykiety wiersza podglądu. Klucz nieznany (backend
 * zwrócił coś spoza tej listy) jest wyświetlany dosłownie — degradacja bez wyjątku.
 */
export const GDPR_PREVIEW_COUNT_LABEL_KEYS: Readonly<Record<string, string>> = {
  customer: 'supervisor.gdprAnonymize.previewCount.customer',
  contact: 'supervisor.gdprAnonymize.previewCount.contact',
  scheduled_callback: 'supervisor.gdprAnonymize.previewCount.scheduledCallback',
  campaign_contact: 'supervisor.gdprAnonymize.previewCount.campaignContact',
  campaign_contact_archive: 'supervisor.gdprAnonymize.previewCount.campaignContactArchive',
  contact_transcription: 'supervisor.gdprAnonymize.previewCount.contactTranscription',
  contact_ai_summary: 'supervisor.gdprAnonymize.previewCount.contactAiSummary',
  email_message: 'supervisor.gdprAnonymize.previewCount.emailMessage',
  social_message: 'supervisor.gdprAnonymize.previewCount.socialMessage',
  contacts_dw: 'supervisor.gdprAnonymize.previewCount.contactsDw',
};
