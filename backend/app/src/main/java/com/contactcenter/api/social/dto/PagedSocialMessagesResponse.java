package com.contactcenter.api.social.dto;

import java.util.List;

/**
 * Wrapper z paginacją dla listy wiadomości social media.
 */
public record PagedSocialMessagesResponse(
        List<SocialMessageResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
