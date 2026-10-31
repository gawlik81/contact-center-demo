package com.contactcenter.domain.retention;

/**
 * Kategorie danych objęte polityką retencji per tenant.
 *
 * <p>Wartości zgodne z CHECK constraint kolumny {@code tenant_retention_policy.data_category}
 * (V082, DB-046) — zmiana zestawu wartości tutaj wymaga równoległej migracji Flyway
 * aktualizującej ograniczenie w bazie.
 *
 * <p>{@code RECORDINGS} jest jedyną kategorią NIE realizowaną przez przyszły
 * {@code RetentionPurgeService} (BE-113) w sensie usuwania wiersza — nagrania są
 * "usuwane" przez wyzerowanie kolumny z URL-em + skasowanie obiektu S3, co obsłuży
 * rozszerzenie {@code RecordingRetentionJob} (BE-116).
 */
public enum RetentionDataCategory {

    /**
     * Historia kontaktów (contact, contact_event, email_message, social_message) — retencja platformowa
     * 60 mies. ({@code contact_ai_summary} należy do {@link #TRANSCRIPTS}, nie tutaj — zob.
     * {@code PartitionReclaimJob} i {@code RetentionPurgeServiceImpl#purgeTranscripts}).
     */
    CONTACT_INTERACTIONS,

    /** Nagrania rozmów (URL w contact.recording_url + obiekt S3) — personalizowalna per tenant. */
    RECORDINGS,

    /** Transkrypcje rozmów i podsumowania AI (contact_transcription, contact_ai_summary) — retencja platformowa 3 mies. (90 dni). */
    TRANSCRIPTS,

    /** Zarchiwizowane dane kampanii (campaign_contact_archive) — retencja platformowa 60 mies. */
    CAMPAIGN_DATA
}
