package com.contactcenter.domain.social;

/**
 * Wynik przetworzenia JEDNEJ STRONY sweepu wiadomości social OSIEROCONYCH ({@code contact_id IS
 * NULL}) wg wieku (EPIC-30, BE-127) — {@link SocialMessageService#purgeOrphansOlderThan}.
 *
 * <p>Analogiczny do {@code com.contactcenter.domain.email.OrphanEmailPurgeBatch}, uproszczony: brak
 * S3 (domena social nie przechowuje obiektów w S3 — jak {@link SocialMessageService#purgeByContactIds}),
 * więc {@code deletedRows} jest zwykłym {@code int} (bez odpowiednika {@code PurgedMessages}).
 *
 * @param deletedRows     liczba faktycznie usuniętych wierszy {@code social_message} (potwierdzona
 *                        przez {@code DELETE … RETURNING}, nie liczba zleconych)
 * @param candidatesFound liczba kandydatów NA STRONIE (nie liczba usuniętych!) — wołający kontynuuje
 *                         pętlę, gdy {@code candidatesFound == batchSize}
 * @param nextCursor      kursor do kolejnego wywołania ({@code null}, gdy {@code candidatesFound == 0})
 */
public record OrphanSocialPurgeBatch(int deletedRows, int candidatesFound, SocialOrphanCursor nextCursor) {
}
