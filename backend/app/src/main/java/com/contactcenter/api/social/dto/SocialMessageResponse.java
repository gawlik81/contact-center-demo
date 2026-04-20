package com.contactcenter.api.social.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO reprezentujący pojedynczą wiadomość social media w odpowiedzi API.
 */
public record SocialMessageResponse(
        UUID messageId,
        String platform,
        String direction,
        String content,
        String attachments,
        Instant sentAt,
        String senderExternalId
) {}
