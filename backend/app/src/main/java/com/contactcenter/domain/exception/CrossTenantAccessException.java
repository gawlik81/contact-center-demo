package com.contactcenter.domain.exception;

import java.util.UUID;

/**
 * Wyjątek rzucany przy próbie dostępu do zasobu należącego do innego tenanta.
 *
 * <p>Zgodnie z kryterium akceptacji BE-002: próba dostępu do zasobu innego
 * tenanta zwraca <strong>HTTP 403</strong> (nie 404). Wyjątek ten jest obsługiwany
 * przez {@code GlobalExceptionHandler.handleCrossTenantAccessException()}.
 *
 * <p>Rzucany przez:
 * <ul>
 *   <li>{@code TenantAwareRepository} – gdy zasób istnieje, ale należy do innego tenanta</li>
 *   <li>{@code CrossTenantAspect} – wykrywa wywołania cross-tenant przez AOP</li>
 *   <li>Serwisy domenowe – gdy weryfikują tenant_id przed operacją</li>
 * </ul>
 *
 * <p><strong>WAŻNE:</strong> Nie zwracaj HTTP 404, nawet jeśli zasób "nie istnieje"
 * z perspektywy tenanta. 404 ujawniałoby informację o istnieniu zasobu w systemie.
 *
 * <h3>Przykład użycia w serwisie:</h3>
 * <pre>{@code
 * Contact contact = contactRepository.findById(contactId)
 *         .orElseThrow(() -> new EntityNotFoundException("Contact not found"));
 *
 * if (!contact.getTenantId().equals(TenantContext.getTenantId())) {
 *     throw new CrossTenantAccessException(contactId, TenantContext.getTenantId(), contact.getTenantId());
 * }
 * }</pre>
 */
public class CrossTenantAccessException extends RuntimeException {

    private final UUID resourceId;
    private final UUID requestingTenantId;
    private final UUID resourceTenantId;

    /**
     * Tworzy wyjątek z pełną informacją o próbie cross-tenant.
     *
     * @param resourceId        UUID zasobu, do którego próbowano uzyskać dostęp
     * @param requestingTenantId UUID tenanta wykonującego żądanie (z TenantContext)
     * @param resourceTenantId  UUID tenanta, do którego należy zasób
     */
    public CrossTenantAccessException(UUID resourceId, UUID requestingTenantId, UUID resourceTenantId) {
        super(String.format(
                "Odmowa dostępu cross-tenant: zasób [%s] należy do tenanta [%s], " +
                "a żądanie pochodzi od tenanta [%s]",
                resourceId, resourceTenantId, requestingTenantId
        ));
        this.resourceId = resourceId;
        this.requestingTenantId = requestingTenantId;
        this.resourceTenantId = resourceTenantId;
    }

    /**
     * Tworzy wyjątek bez ujawniania, do którego tenanta należy zasób
     * (bezpieczniejszy wariant gdy resourceTenantId nie powinien być logowany).
     *
     * @param resourceId        UUID zasobu
     * @param requestingTenantId UUID tenanta wykonującego żądanie
     */
    public CrossTenantAccessException(UUID resourceId, UUID requestingTenantId) {
        super(String.format(
                "Odmowa dostępu: zasób [%s] jest niedostępny dla tenanta [%s]",
                resourceId, requestingTenantId
        ));
        this.resourceId = resourceId;
        this.requestingTenantId = requestingTenantId;
        this.resourceTenantId = null;
    }

    public UUID getResourceId() {
        return resourceId;
    }

    public UUID getRequestingTenantId() {
        return requestingTenantId;
    }

    public UUID getResourceTenantId() {
        return resourceTenantId;
    }
}
