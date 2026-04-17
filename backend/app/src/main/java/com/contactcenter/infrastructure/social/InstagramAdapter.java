package com.contactcenter.infrastructure.social;

import com.contactcenter.domain.model.SocialPlatform;
import com.contactcenter.domain.social.SocialMediaAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Stub adaptera Instagram Direct.
 *
 * <p>W produkcji wywoływałby Instagram Graph API (v18.0+):
 * <ul>
 *   <li>Wysyłka: {@code POST /v18.0/me/messages} (taki sam endpoint jak Facebook,
 *       różni się access tokenem i typem konta Instagram Business)</li>
 *   <li>Historia: {@code GET /v18.0/{conversation_id}/messages}</li>
 * </ul>
 *
 * <p>Aktualna implementacja jest stubem – loguje operacje i symuluje zachowanie.
 */
@Slf4j
@Component
public class InstagramAdapter implements SocialMediaAdapter {

    @Override
    public SocialPlatform getPlatform() {
        return SocialPlatform.INSTAGRAM;
    }

    @Override
    public void sendMessage(UUID integrationId, String recipientExternalId,
                            String content, List<String> attachmentUrls) {
        log.info("[InstagramAdapter][STUB] Wysyłam wiadomość: integrationId={}, recipient={}, " +
                 "contentLength={}, attachments={}",
                integrationId, recipientExternalId,
                content != null ? content.length() : 0,
                attachmentUrls != null ? attachmentUrls.size() : 0);

        // STUB: W produkcji wywołaj:
        // POST https://graph.facebook.com/v18.0/me/messages
        // Body: { "recipient": {"id": recipientExternalId}, "message": {"text": content} }
        // Authorization: Bearer {accessToken z integracji Instagram Business}

        log.info("[InstagramAdapter][STUB] Wiadomość wysłana (symulacja): recipient={}", recipientExternalId);
    }

    @Override
    public List<SocialMessageDto> getConversationHistory(UUID integrationId,
                                                          String conversationId, int limit) {
        log.info("[InstagramAdapter][STUB] Pobieranie historii konwersacji: integrationId={}, " +
                 "conversationId={}, limit={}", integrationId, conversationId, limit);

        // STUB: W produkcji wywołaj Instagram Conversations API
        return Collections.emptyList();
    }
}
