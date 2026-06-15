package com.contactcenter.infrastructure.social;

import com.contactcenter.domain.social.SocialPlatform;
import com.contactcenter.domain.social.SocialMediaAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Stub adaptera WhatsApp Business.
 *
 * <p>W produkcji wywoływałby WhatsApp Business API (Cloud API):
 * <ul>
 *   <li>Wysyłka: {@code POST /v18.0/{phone_number_id}/messages}</li>
 *   <li>Historia: dostępna przez webhook – WhatsApp nie udostępnia API odczytu historii</li>
 * </ul>
 *
 * <p>Aktualna implementacja jest stubem – loguje operacje i symuluje zachowanie.
 */
@Slf4j
@Component
public class WhatsAppAdapter implements SocialMediaAdapter {

    @Override
    public SocialPlatform getPlatform() {
        return SocialPlatform.WHATSAPP;
    }

    @Override
    public void sendMessage(UUID integrationId, String recipientExternalId,
                            String content, List<String> attachmentUrls) {
        log.info("[WhatsAppAdapter][STUB] Wysyłam wiadomość: integrationId={}, recipient={}, " +
                 "contentLength={}, attachments={}",
                integrationId, recipientExternalId,
                content != null ? content.length() : 0,
                attachmentUrls != null ? attachmentUrls.size() : 0);

        // STUB: W produkcji wywołaj:
        // POST https://graph.facebook.com/v18.0/{phone_number_id}/messages
        // Body: {
        //   "messaging_product": "whatsapp",
        //   "to": recipientExternalId,
        //   "type": "text",
        //   "text": {"body": content}
        // }
        // Authorization: Bearer {accessToken z integracji WhatsApp Business}

        log.info("[WhatsAppAdapter][STUB] Wiadomość wysłana (symulacja): recipient={}", recipientExternalId);
    }

    @Override
    public List<SocialMessageDto> getConversationHistory(UUID integrationId,
                                                          String conversationId, int limit) {
        log.info("[WhatsAppAdapter][STUB] Pobieranie historii: integrationId={}, conversationId={}, limit={}",
                integrationId, conversationId, limit);

        // STUB: WhatsApp Business API nie udostępnia endpoint do odczytu historii.
        // Historia budowana jest wyłącznie z webhooków (baza danych lokalna).
        return Collections.emptyList();
    }
}
