package com.contactcenter.domain.social;

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
}
