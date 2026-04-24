package com.contactcenter.api.customer.dto;

import com.contactcenter.domain.model.Customer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Odpowiedź API z pełnymi danymi profilu klienta.
 *
 * <p>Używana przez GET /api/customers/{id}, POST /api/customers, PATCH /api/customers/{id}
 * oraz przez listing/search klientów w Agent Desktop.
 * Nie zawiera danych po anonimizacji RODO (po DELETE klient jest oznaczony is_deleted=true).
 */
public record CustomerResponse(
        UUID customerId,
        UUID tenantId,
        String firstName,
        String lastName,
        List<String> phone,
        List<String> email,
        Map<String, Object> customFields,
        Map<String, Object> gdprConsent,
        String source,
        boolean deleted,
        Instant createdAt,
        Instant updatedAt,
        /** Czas ostatniego zakończonego kontaktu – null gdy klient nie ma żadnych kontaktów. */
        Instant lastContactAt
) {

    /**
     * Mapuje encję {@link Customer} na DTO odpowiedzi bez daty ostatniego kontaktu.
     *
     * <p>Używane przy operacjach zapisu (create, update) gdzie pobranie lastContactAt
     * wymagałoby dodatkowego zapytania nieistotnego w tym kontekście.
     *
     * @param customer encja klienta z bazy danych
     * @return DTO gotowe do zwrócenia przez API (lastContactAt = null)
     */
    public static CustomerResponse from(Customer customer) {
        return new CustomerResponse(
                customer.getCustomerId(),
                customer.getTenantId(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getPhone() != null ? customer.getPhone() : List.of(),
                customer.getEmail() != null ? customer.getEmail() : List.of(),
                customer.getCustomFields(),
                customer.getGdprConsent(),
                customer.getSource(),
                customer.isDeleted(),
                customer.getCreatedAt(),
                customer.getUpdatedAt(),
                null
        );
    }

    /**
     * Mapuje encję {@link Customer} na DTO odpowiedzi z datą ostatniego kontaktu.
     *
     * <p>Używane przez listing i search klientów w Agent Desktop (zakładka Klienci).
     *
     * @param customer      encja klienta z bazy danych
     * @param lastContactAt czas ostatniego zakończonego kontaktu lub null
     * @return DTO gotowe do zwrócenia przez API
     */
    public static CustomerResponse from(Customer customer, Instant lastContactAt) {
        return new CustomerResponse(
                customer.getCustomerId(),
                customer.getTenantId(),
                customer.getFirstName(),
                customer.getLastName(),
                customer.getPhone() != null ? customer.getPhone() : List.of(),
                customer.getEmail() != null ? customer.getEmail() : List.of(),
                customer.getCustomFields(),
                customer.getGdprConsent(),
                customer.getSource(),
                customer.isDeleted(),
                customer.getCreatedAt(),
                customer.getUpdatedAt(),
                lastContactAt
        );
    }
}
