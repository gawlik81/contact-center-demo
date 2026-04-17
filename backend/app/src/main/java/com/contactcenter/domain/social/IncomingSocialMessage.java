package com.contactcenter.domain.social;

import com.contactcenter.domain.model.SocialPlatform;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * DTO przychodzącego zdarzenia social media.
 *
 * <p>Tworzony przez {@link com.contactcenter.api.social.SocialWebhookController}
 * po sparsowaniu payloadu webhooka od platformy. Przekazywany przez RabbitMQ
 * do {@link SocialMessageService#processIncomingMessage(IncomingSocialMessage)}.
 *
 * @param platform           platforma social media (FACEBOOK/INSTAGRAM/WHATSAPP)
 * @param pageId             ID strony/konta tenanta na platformie – używany do identyfikacji tenanta
 * @param senderExternalId   ID nadawcy na platformie
 * @param externalMessageId  unikalny identyfikator wiadomości na platformie (idempotentność)
 * @param content            treść tekstowa wiadomości (może być null dla wiadomości z samymi załącznikami)
 * @param attachments        lista metadanych załączników (może być null)
 * @param sentAt             czas wysłania wiadomości na platformie
 */
public record IncomingSocialMessage(
        SocialPlatform platform,
        String pageId,
        String senderExternalId,
        String externalMessageId,
        String content,
        List<Map<String, Object>> attachments,
        Instant sentAt
) {}
