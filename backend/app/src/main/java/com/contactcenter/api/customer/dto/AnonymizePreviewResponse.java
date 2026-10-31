package com.contactcenter.api.customer.dto;

import java.util.Map;

/**
 * Odpowiedź podglądu anonimizacji RODO (BE-129, DB-062 {@code p_dry_run = TRUE}).
 *
 * <p>Używana przez {@code GET /api/customers/{id}/gdpr/anonymize/preview} — woła funkcję SQL
 * {@code anonymize_customer} w trybie podglądu, bez żadnego efektu ubocznego (baza i S3
 * nietknięte, brak wpisu audytowego). Pozwala UI (FE-112) pokazać operatorowi zakres
 * planowanej anonimizacji przed potwierdzeniem.
 *
 * @param dryRun              zawsze {@code true} dla tej odpowiedzi — pole zachowane 1:1 z kontraktu
 *                            JSONB funkcji SQL, żeby FE mogło odróżnić odpowiedź podglądu od
 *                            rzeczywistej bez polegania wyłącznie na tym, który endpoint wywołano
 * @param counts              liczba rekordów, które zostałyby zmienione/usunięte w każdej tabeli
 *                            (klucze: {@code customer, contact, scheduled_callback, campaign_contact,
 *                            campaign_contact_archive, contact_transcription, contact_ai_summary,
 *                            email_message, social_message, contacts_dw})
 * @param matchedByLink       liczba encji zbioru podmiotu dopasowanych kluczem obcym (D9 = A)
 * @param matchedByIdentifier liczba encji zbioru podmiotu dopasowanych wyłącznie znormalizowanym
 *                            telefonem/e-mailem klienta (D9 = A) — ryzyko R9 (numer/adres wspólny
 *                            dla rodziny/centrali): operator powinien zweryfikować te dopasowania
 *                            przed potwierdzeniem rzeczywistej anonimizacji
 * @param s3ObjectsToDelete   liczba unikalnych obiektów S3 (nagrania, EML, załączniki e-mail/social),
 *                            które zostałyby usunięte po commit rzeczywistej anonimizacji
 */
public record AnonymizePreviewResponse(
        boolean dryRun,
        Map<String, Integer> counts,
        int matchedByLink,
        int matchedByIdentifier,
        int s3ObjectsToDelete
) {
}
