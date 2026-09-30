package com.contactcenter.domain.social;

import java.time.Instant;
import java.util.UUID;

/**
 * Kursor stronicowania keyset dla sweepu wiadomości social OSIEROCONYCH ({@code contact_id IS NULL})
 * wg wieku (EPIC-30, BE-127).
 *
 * <p>Analogiczny do {@code com.contactcenter.domain.email.EmailOrphanCursor} — tu „wiekiem" jest po
 * prostu {@code sent_at} (kolumna {@code NOT NULL}, bez potrzeby {@code COALESCE}). Niesie
 * {@code messageId} obok {@code messageAt}, bo {@code sent_at} sam w sobie nie jest unikalny.
 *
 * <p>Publiczny — {@code RetentionPurgeServiceImpl} (domain.retention) trzyma ten kursor między
 * iteracjami pętli sweepu i przekazuje go z powrotem do
 * {@link SocialMessageService#purgeOrphansOlderThan(java.util.UUID, SocialOrphanCursor, Instant, int)}.
 *
 * @param messageAt wartość {@code sent_at} ostatniego kandydata poprzedniej strony
 * @param messageId {@code message_id} ostatniego kandydata poprzedniej strony (tie-break)
 */
public record SocialOrphanCursor(Instant messageAt, UUID messageId) {
}
