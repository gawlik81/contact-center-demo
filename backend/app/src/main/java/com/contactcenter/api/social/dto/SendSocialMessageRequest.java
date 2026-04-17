package com.contactcenter.api.social.dto;

import java.util.List;

/**
 * Request DTO do wysyłki wiadomości social media przez agenta.
 *
 * @param content        treść wiadomości
 * @param attachmentUrls lista URL załączników (opcjonalna)
 */
public record SendSocialMessageRequest(
        String content,
        List<String> attachmentUrls
) {}
