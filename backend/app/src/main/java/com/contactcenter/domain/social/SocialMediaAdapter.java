package com.contactcenter.domain.social;

import java.util.List;
import java.util.UUID;

/**
 * Interfejs adaptera platformy social media.
 *
 * <p>Każda platforma (Facebook, Instagram, WhatsApp) implementuje ten interfejs.
 * Implementacje rejestrowane są w {@link SocialAdapterRegistry} i wybierane
 * dynamicznie na podstawie platformy.
 *
 * <p>W środowisku produkcyjnym implementacje wywoływałyby Graph API / WhatsApp Business API.
 * W aktualnej wersji są to stuby logujące operacje (analogicznie do MockTelephonyAdapter).
 */
public interface SocialMediaAdapter {

    /**
     * Zwraca platformę obsługiwaną przez ten adapter.
     *
     * @return platforma social media
     */
    SocialPlatform getPlatform();

    /**
     * Wysyła wiadomość do odbiorcy na platformie.
     *
     * <p>W produkcji: wywołuje Graph API / WhatsApp Business API.
     * W stub: loguje INFO i symuluje wysyłkę.
     *
     * @param integrationId      UUID integracji (dostarcza access token)
     * @param recipientExternalId ID odbiorcy na platformie
     * @param content            treść wiadomości tekstowej
     * @param attachmentUrls     lista URL załączników (może być pusta)
     */
    void sendMessage(UUID integrationId, String recipientExternalId, String content, List<String> attachmentUrls);

    /**
     * Pobiera historię konwersacji z platformy.
     *
     * <p>W produkcji: wywołuje Graph API.
     * W stub: zwraca pustą listę.
     *
     * @param integrationId  UUID integracji
     * @param conversationId ID konwersacji na platformie
     * @param limit          maksymalna liczba wiadomości do pobrania
     * @return lista DTO wiadomości (stub zwraca pustą listę)
     */
    List<SocialMessageDto> getConversationHistory(UUID integrationId, String conversationId, int limit);

    // =========================================================================
    // DTO wiadomości (wewnętrzne DTO dla tego interfejsu)
    // =========================================================================

    /**
     * DTO reprezentujące wiadomość pobraną z platformy social media.
     */
    record SocialMessageDto(
            String externalMessageId,
            String senderExternalId,
            String content,
            java.time.Instant sentAt
    ) {}
}
