package com.contactcenter.api.phonenumber.dto;

import com.contactcenter.domain.phonenumber.PhoneNumber;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO odpowiedzi z danymi numeru telefonu.
 *
 * <p>Mapuje encję {@link PhoneNumber} na format JSON zwracany przez API.
 * Pole {@code isDeleted} jest celowo pominięte – klient nigdy nie widzi
 * logicznie usuniętych rekordów.
 */
public record PhoneNumberResponse(
        UUID phoneNumberId,
        UUID tenantId,
        String number,
        String displayName,
        boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Fabryka tworząca DTO z encji JPA.
     *
     * @param phoneNumber encja numeru telefonu
     * @return zmapowane DTO odpowiedzi
     */
    public static PhoneNumberResponse from(PhoneNumber phoneNumber) {
        return new PhoneNumberResponse(
                phoneNumber.getPhoneNumberId(),
                phoneNumber.getTenantId(),
                phoneNumber.getNumber(),
                phoneNumber.getDisplayName(),
                phoneNumber.isActive(),
                phoneNumber.getCreatedAt(),
                phoneNumber.getUpdatedAt()
        );
    }
}
