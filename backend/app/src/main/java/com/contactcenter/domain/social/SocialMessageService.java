package com.contactcenter.domain.social;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Serwis przetwarzający wiadomości social media.
 *
 * <p>Główne odpowiedzialności:
 * <ol>
 *   <li>Przetwarzanie przychodzących wiadomości z webhooka (przez RabbitMQ):
 *       identyfikacja tenanta → idempotentność → zarządzanie kontaktem → zapis wiadomości → routing</li>
 *   <li>Wysyłka wiadomości przez agenta: pobranie kontaktu → wybór adaptera → wysyłka → zapis</li>
 * </ol>
 *
 * <p><strong>Multi-tenancy w webhookach:</strong> Webhooki platform social media nie zawierają JWT.
 * Tenant jest identyfikowany przez parę (platform, pageId) – każda strona/konto należy do jednego tenanta.
 * TenantContext jest ustawiany ręcznie i czyszczony w bloku finally.
 *
 * <p>Analogiczny wzorzec do {@link com.contactcenter.domain.email.EmailContactCreator}.
 */
public interface SocialMessageService {

    /**
     * Przetwarza przychodzącą wiadomość social media z webhooka.
     *
     * <p>Flow:
     * <ol>
     *   <li>Znajdź integrację po (platform, pageId) we wszystkich tenantach</li>
     *   <li>Ustaw TenantContext na podstawie tenanta integracji</li>
     *   <li>Sprawdź idempotentność – jeśli wiadomość już istnieje, pomiń</li>
     *   <li>Znajdź aktywny kontakt lub utwórz nowy</li>
     *   <li>Zapisz SocialMessage z direction=INBOUND</li>
     *   <li>Dla nowego kontaktu – opublikuj ContactQueuedMessage do routingu</li>
     * </ol>
     *
     * @param incoming DTO przychodzącego zdarzenia (sparsowane z webhooka)
     */
    void processIncomingMessage(IncomingSocialMessage incoming);

    /**
     * Wysyła wiadomość do klienta przez agenta.
     *
     * <p>Flow:
     * <ol>
     *   <li>Pobierz kontakt i zweryfikuj że jest SOCIAL_*</li>
     *   <li>Znajdź integrację pasującą do kanału kontaktu</li>
     *   <li>Wywołaj odpowiedni adapter</li>
     *   <li>Zapisz SocialMessage z direction=OUTBOUND</li>
     * </ol>
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta (z TenantContext)
     * @param content   treść wiadomości
     * @param attachmentUrls lista URL załączników (może być pusta)
     * @throws IllegalArgumentException gdy kontakt nie istnieje lub nie jest kanałem social
     */
    void sendMessage(UUID contactId, UUID tenantId, String content, List<String> attachmentUrls);

    /**
     * Pobiera historię wiadomości social media dla kontaktu (do 50 najnowszych, {@code sentAt DESC}).
     *
     * <p>Paginacja po stronie API – ta metoda zwraca pełną (ograniczoną do 50) listę,
     * a kontroler wykonuje wycinanie strony i mapowanie na DTO.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     * @return lista wiadomości posortowanych po {@code sentAt DESC}
     */
    List<SocialMessage> getRecentMessagesForContact(UUID contactId, UUID tenantId);

    /**
     * Zeruje {@code contact_id} dla wiadomości wskazujących na usuwane kontakty (retencja EPIC-29,
     * BE-113 – kategoria CONTACT_INTERACTIONS). Nie usuwa wierszy {@code social_message}, tylko
     * odcina referencję (kolumna nullable od V028).
     *
     * @param tenantId   UUID tenanta
     * @param contactIds lista UUID usuniętych kontaktów – pusta lista jest no-opem
     * @return liczba zaktualizowanych wierszy
     * @deprecated EPIC-30 (D1 = A, BE-125): purge kontaktu usuwa wiadomości — użyj
     *             {@link #purgeByContactIds}. Podpięcie w {@code RetentionPurgeServiceImpl} i usunięcie
     *             tej metody: BE-126 (przy D1 = C BE-130 przywraca odcinanie referencji).
     */
    @Deprecated
    int detachContactReferences(UUID tenantId, List<UUID> contactIds);

    /**
     * Usuwa wiadomości social wskazanych kontaktów (retencja EPIC-30, BE-125, założenie D1 = A —
     * usuwanie zamiast odcinania).
     *
     * <p>Bez operacji S3: domena social nie przechowuje obiektów w S3. Pojedyncze {@code DELETE} po
     * {@code (tenant_id, contact_id IN (...))} — wynik to liczba usuniętych wierszy (brak
     * {@code contactIdsBlocked}: nic nie może zablokować usunięcia kontaktu).
     *
     * <p><strong>Prekontrakt:</strong> {@code TenantContext} ustawiony na {@code tenantId}
     * (wołający zarządza kontekstem; metoda NIGDY go nie czyści).
     *
     * @param tenantId   UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param contactIds kontakty, których wiadomości mają zostać usunięte — {@code null}/pusta lista to no-op
     * @return liczba usuniętych wierszy {@code social_message}
     * @throws IllegalStateException gdy {@code TenantContext} nie jest ustawiony (przy niepustej liście)
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     *         (przy niepustej liście)
     */
    int purgeByContactIds(UUID tenantId, List<UUID> contactIds);

    // =========================================================================
    // BE-127: Retencja – sweep wiadomości OSIEROCONYCH (contact_id IS NULL) wg wieku (EPIC-30)
    // =========================================================================

    /**
     * Liczy wiadomości social OSIEROCONE ({@code contact_id IS NULL}) starsze niż {@code cutoff} —
     * dry-run (WP-4) i dashboard/badge (BE-128). Bez filtra resztkowego (patrz Javadoc
     * {@code SocialMessageRepository#countOrphansOlderThan} — {@code contact_id} jest zawsze
     * ustawiany synchronicznie przed zapisem, więc „świeża sierota" nie istnieje strukturalnie).
     *
     * @param tenantId UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param cutoff   granica czasowa retencji CONTACT_INTERACTIONS
     * @return liczba kwalifikujących się wiadomości
     * @throws IllegalStateException gdy {@code TenantContext} nie jest ustawiony
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    long countOrphansOlderThan(UUID tenantId, Instant cutoff);

    // =========================================================================
    // BE-128: Retencja – liczenie wiadomości POWIĄZANYCH z kontaktami kwalifikującymi się (EPIC-30)
    // =========================================================================

    /**
     * Liczy wiadomości social POWIĄZANE z kontaktem, którego kontakt SAM kwalifikuje się do
     * usunięcia w ramach kategorii CONTACT_INTERACTIONS — dashboard/badge (BE-128), składnik
     * uzupełniający sieroty ({@link #countOrphansOlderThan}) w {@code eligibleRowCount}
     * ({@code RetentionSummaryDto}, patrz Javadoc tam po pełną semantykę i uzasadnienie kosztu).
     *
     * @param tenantId UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param cutoff   granica czasowa retencji CONTACT_INTERACTIONS — kandydują wiadomości, których
     *                 kontakt ma {@code started_at < cutoff}
     * @return liczba kwalifikujących się wiadomości (nigdy ujemna)
     * @throws IllegalStateException gdy {@code TenantContext} nie jest ustawiony
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    long countLinkedToContactsOlderThan(UUID tenantId, Instant cutoff);

    /**
     * Usuwa JEDNĄ STRONĘ wiadomości social OSIEROCONYCH ({@code contact_id IS NULL}) starszych niż
     * {@code cutoff} — retencja EPIC-30, BE-127 (założenie D1 = A, ta sama flaga bezpiecznika co
     * BE-126: {@code retention.purge.delete-messages}). Bez S3 (jak {@link #purgeByContactIds}).
     *
     * <p><strong>Stronicowanie keyset (strategia H-1, jak BE-126):</strong> wywołujący
     * ({@code RetentionPurgeServiceImpl}) trzyma {@link SocialOrphanCursor} między wywołaniami i
     * kontynuuje pętlę, gdy {@link OrphanSocialPurgeBatch#candidatesFound()} == {@code batchSize}.
     *
     * <p><strong>Prekontrakt:</strong> {@code TenantContext} ustawiony na {@code tenantId}
     * (wołający zarządza kontekstem; metoda NIGDY go nie czyści).
     *
     * @param tenantId  UUID tenanta (musi zgadzać się z {@code TenantContext})
     * @param cursor    kursor poprzedniej strony ({@code null} dla pierwszej strony)
     * @param cutoff    granica czasowa retencji CONTACT_INTERACTIONS
     * @param batchSize maksymalna liczba kandydatów na stronę
     * @return wynik strony: liczba usuniętych wierszy, liczba kandydatów na stronie i kursor kolejnej strony
     * @throws IllegalStateException gdy {@code TenantContext} nie jest ustawiony
     * @throws com.contactcenter.domain.exception.CrossTenantAccessException gdy tenantId != kontekst
     */
    OrphanSocialPurgeBatch purgeOrphansOlderThan(UUID tenantId, SocialOrphanCursor cursor, Instant cutoff, int batchSize);
}
