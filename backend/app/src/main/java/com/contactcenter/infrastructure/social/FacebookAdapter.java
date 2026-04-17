package com.contactcenter.infrastructure.social;

import com.contactcenter.domain.model.SocialPlatform;
import com.contactcenter.domain.social.SocialMediaAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Stub adaptera Facebook Messenger.
 *
 * <p>W produkcji wywoływałby Facebook Graph API (v18.0+):
 * <ul>
 *   <li>Wysyłka: {@code POST /v18.0/me/messages} z access tokenem strony</li>
 *   <li>Historia: {@code GET /v18.0/{conversation_id}/messages}</li>
 * </ul>
 *
 * <p>Aktualna implementacja jest stubem – loguje operacje i symuluje zachowanie,
 * analogicznie do {@code MockTelephonyAdapter}.
 */
@Slf4j
@Component
public class FacebookAdapter implements SocialMediaAdapter {

    @Override
    public SocialPlatform getPlatform() {
        return SocialPlatform.FACEBOOK;
    }

    @Override
    public void sendMessage(UUID integrationId, String recipientExternalId,
                            String content, List<String> attachmentUrls) {
        log.info("[FacebookAdapter][STUB] Wysyłam wiadomość: integrationId={}, recipient={}, " +
                 "contentLength={}, attachments={}",
                integrationId, recipientExternalId,
                content != null ? content.length() : 0,
                attachmentUrls != null ? attachmentUrls.size() : 0);

        // STUB: W produkcji wywołaj:
        // POST https://graph.facebook.com/v18.0/me/messages
        // Body: { "recipient": {"id": recipientExternalId}, "message": {"text": content} }
        // Authorization: Bearer {accessToken z integracji}

        log.info("[FacebookAdapter][STUB] Wiadomość wysłana (symulacja): recipient={}", recipientExternalId);
    }

    @Override
    public List<SocialMessageDto> getConversationHistory(UUID integrationId,
                                                          String conversationId, int limit) {
        log.info("[FacebookAdapter][STUB] Pobieranie historii konwersacji: integrationId={}, " +
                 "conversationId={}, limit={}", integrationId, conversationId, limit);

        // STUB: W produkcji wywołaj:
        // GET https://graph.facebook.com/v18.0/{conversationId}/messages?limit={limit}
        // Zwróć zmapowane wiadomości do SocialMessageDto

        return Collections.emptyList();
    }
}
