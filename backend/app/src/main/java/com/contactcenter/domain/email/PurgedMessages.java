package com.contactcenter.domain.email;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Wynik usuwania wiadomości e-mail wraz z obiektami S3 ({@link EmailMessageService#purgeByContactIds},
 * retencja EPIC-30, BE-125).
 *
 * <p>Kolejność operacji to „S3 przed wierszem": wiersz {@code email_message} jest usuwany
 * wyłącznie po skutecznym usunięciu WSZYSTKICH jego obiektów S3. Wiadomość, której obiektu nie
 * udało się usunąć, zostaje w bazie (ponowi ją następny purge — {@code DeleteObject} jest
 * idempotentne), a jej kontakt trafia do {@link #contactIdsBlocked()}.
 *
 * @param deletedRows       liczba faktycznie usuniętych wierszy {@code email_message} (potwierdzona
 *                          przez {@code DELETE … RETURNING}, nie przez liczbę zleconych)
 * @param s3ObjectsDeleted  liczba różnych kluczy S3, dla których usunięcie się powiodło (brak
 *                          obiektu też jest sukcesem — {@code DeleteObject} jest idempotentne)
 * @param s3Failures        liczba różnych kluczy S3, których usunięcie się NIE powiodło (błąd
 *                          usługi lub klienta); po przekroczeniu progu kolejnych porażek z rzędu
 *                          dalsze klucze nie są próbowane i nie są tu liczone, ale ich wiadomości
 *                          również zostają, a kontakty trafiają do {@link #contactIdsBlocked()}
 * @param s3Rejected        liczba różnych kluczy pominiętych przez allow-listę prefiksu
 *                          {@code email-attachments/{tenantId}/} (cudzy obiekt — nie jest ruszany);
 *                          wiersz takiej wiadomości jest usuwany mimo to (PII musi zniknąć)
 * @param contactIdsBlocked kontakty, które mają ≥ 1 wiadomość NIEusuniętą (porażka S3, przerwana
 *                          faza S3 albo wiersz niepotwierdzony przez {@code DELETE … RETURNING}).
 *                          Wołający (BE-126) usuwa kontakty WYŁĄCZNIE spoza tego zbioru — inaczej
 *                          gubi wskaźnik ({@code attachments}) do niesprzątniętych obiektów
 */
public record PurgedMessages(
        int deletedRows,
        int s3ObjectsDeleted,
        int s3Failures,
        int s3Rejected,
        Set<UUID> contactIdsBlocked) {

    /**
     * Zbiór blokad jest kopią obronną i niemodyfikowalny, ale — celowo — NIE jest to
     * {@code Set.copyOf}/{@code Set.of}: te rzucają {@link NullPointerException} z
     * {@code contains(null)}, a wołający (BE-126/127) mają w rękach {@code contactId} mogące być
     * {@code null} (wiadomości osierocone) i naturalnie napiszą {@code blocked.contains(row.contactId())}.
     * Zbiór nigdy nie zawiera {@code null} (wtedy {@code contains(null)} = {@code false}); element
     * {@code null} na wejściu to błąd wołającego i jest odrzucany jak dawniej.
     */
    public PurgedMessages {
        Set<UUID> copy = new HashSet<>();
        if (contactIdsBlocked != null) {
            copy.addAll(contactIdsBlocked);
        }
        if (copy.contains(null)) {
            throw new NullPointerException("contactIdsBlocked nie może zawierać null");
        }
        contactIdsBlocked = Collections.unmodifiableSet(copy);
    }

    /** Wynik „nic nie usunięto, nic nie zablokowano". */
    public static PurgedMessages empty() {
        return new PurgedMessages(0, 0, 0, 0, Set.of());
    }

    /**
     * Sumuje dwa wyniki (np. kolejne partie pętli purge): liczniki się dodają, zbiory zablokowanych
     * kontaktów łączą.
     */
    public PurgedMessages plus(PurgedMessages other) {
        Set<UUID> blocked = new HashSet<>(contactIdsBlocked);
        blocked.addAll(other.contactIdsBlocked);
        return new PurgedMessages(
                deletedRows + other.deletedRows,
                s3ObjectsDeleted + other.s3ObjectsDeleted,
                s3Failures + other.s3Failures,
                s3Rejected + other.s3Rejected,
                blocked);
    }
}
