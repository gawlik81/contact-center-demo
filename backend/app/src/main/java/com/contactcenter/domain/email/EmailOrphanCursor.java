package com.contactcenter.domain.email;

import java.time.Instant;
import java.util.UUID;

/**
 * Kursor stronicowania keyset dla sweepu wiadomości e-mail OSIEROCONYCH ({@code contact_id IS NULL})
 * wg wieku (EPIC-30, BE-127).
 *
 * <p>Analogiczny do {@code com.contactcenter.domain.contact.ContactPurgeCandidate} (BE-126): niesie
 * wartość „wieku wiadomości" ({@code COALESCE(received_at, sent_at, created_at)} — DOKŁADNIE
 * wyrażenie z DB-059/{@code EmailMessageRepository}) obok {@code messageId}, bo sam wiek nie jest
 * unikalny (dwie wiadomości mogą mieć identyczną wartość co do mikrosekundy) — porządek
 * {@code ORDER BY <wiek>, message_id} wymaga obu kolumn do deterministycznego stronicowania.
 *
 * <p>Publiczny — {@code RetentionPurgeServiceImpl} (domain.retention) trzyma ten kursor między
 * iteracjami pętli sweepu i przekazuje go z powrotem do
 * {@link EmailMessageService#purgeOrphansOlderThan(java.util.UUID, EmailOrphanCursor, Instant, int)}.
 *
 * @param messageAt wartość wyrażenia „wiek wiadomości" ostatniego kandydata poprzedniej strony
 * @param messageId {@code message_id} ostatniego kandydata poprzedniej strony (tie-break)
 */
public record EmailOrphanCursor(Instant messageAt, UUID messageId) {
}
