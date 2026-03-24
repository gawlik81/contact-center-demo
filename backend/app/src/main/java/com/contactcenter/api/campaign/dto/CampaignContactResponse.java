package com.contactcenter.api.campaign.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * DTO reprezentujący pojedynczy rekord kontaktu kampanii.
 *
 * <p>Zwracany przez {@code GET /api/campaigns/{id}/contacts}
 * w paginowanej liście {@link com.contactcenter.api.PagedResponse}.
 *
 * <p>Pole {@code customFields} pochodzi z kolumny JSONB – może być {@code null}
 * gdy rekord nie miał dodatkowych pól w pliku CSV.
 */
public record CampaignContactResponse(
        UUID recordId,
        String phone,
        String firstName,
        String lastName,
        Map<String, String> customFields,
        String status,
        String dispositionCode,
        Instant createdAt
) {}
