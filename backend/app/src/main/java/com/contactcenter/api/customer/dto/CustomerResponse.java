package com.contactcenter.api.customer.dto;

import com.contactcenter.domain.model.Customer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Odpowiedź API z pełnymi danymi profilu klienta.
 *
 * <p>Używana przez GET /api/customers/{id}, POST /api/customers, PATCH /api/customers/{id}.
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
        Instant updatedAt
) {

    /**
     * Mapuje encję {@link Customer} na DTO odpowiedzi.
     *
     * @param customer encja klienta z bazy danych
     * @return DTO gotowe do zwrócenia przez API
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
                customer.getUpdatedAt()
        );
    }
}
