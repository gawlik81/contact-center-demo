package com.contactcenter.domain.email;

/**
 * Wynik przetworzenia JEDNEJ STRONY sweepu wiadomości e-mail OSIEROCONYCH ({@code contact_id IS
 * NULL}) wg wieku (EPIC-30, BE-127) — {@link EmailMessageService#purgeOrphansOlderThan}.
 *
 * <p>W odróżnieniu od {@link PurgedMessages} (który sam nie mówi nic o tym, ile kandydatów było W
 * OGÓLE na stronie, ani jaki jest kursor kolejnej strony), ten rekord niesie WSZYSTKO, czego
 * potrzebuje pętla wołającego ({@code RetentionPurgeServiceImpl}) do zaimplementowania strategii H-1
 * (stronicowanie keyset, kursor przesuwa się o CAŁĄ stronę niezależnie od tego, ile wiadomości
 * faktycznie usunięto — patrz {@code ContactRepository#findContactIdsOlderThan} dla precedensu z
 * BE-126):
 * <ul>
 *   <li>{@code purgedMessages} — liczniki S3/DELETE tej strony (reużywa {@link PurgedMessages},
 *       {@code contactIdsBlocked} jest zawsze pusty dla sierot — nie ma kontaktu do zablokowania),</li>
 *   <li>{@code candidatesFound} — liczba kandydatów NA STRONIE (nie liczba usuniętych!) — wołający
 *       kontynuuje pętlę, gdy {@code candidatesFound == batchSize} (strona pełna, mogą być kolejne),</li>
 *   <li>{@code nextCursor} — kursor ostatniego kandydata tej strony (niezależnie od tego, czy jego
 *       wiadomość została usunięta), do przekazania w następnym wywołaniu; {@code null} gdy strona
 *       była pusta (nieistotne — pętla się zatrzymuje).</li>
 * </ul>
 *
 * @param purgedMessages  wynik fazy S3+DELETE tej strony (jak {@link PurgedMessages})
 * @param candidatesFound liczba kandydatów zwróconych przez SELECT tej strony (0..batchSize)
 * @param nextCursor      kursor do kolejnego wywołania ({@code null}, gdy {@code candidatesFound == 0})
 */
public record OrphanEmailPurgeBatch(
        PurgedMessages purgedMessages,
        int candidatesFound,
        EmailOrphanCursor nextCursor) {
}
