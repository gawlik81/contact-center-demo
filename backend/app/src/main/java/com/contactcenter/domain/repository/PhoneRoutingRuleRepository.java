package com.contactcenter.domain.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Repozytorium dla tabeli {@code phone_routing_rule}.
 *
 * <p>Rozszerza {@link TenantAwareRepository}. W tej wersji zawiera tylko
 * metodę do sprawdzania czy istnieją aktywne reguły routingu dla numeru
 * (wymagane przez {@code PhoneNumberService} przy soft delete – BE-033).
 *
 * <p>Pełne CRUD dla reguł routingu zostanie zaimplementowane w BE-034.
 */
@Slf4j
@Repository
public class PhoneRoutingRuleRepository extends TenantAwareRepository {

    /**
     * Sprawdza czy dla podanego numeru istnieje co najmniej jedna aktywna reguła routingu.
     *
     * <p>Używane przy próbie usunięcia numeru telefonu – jeśli istnieją aktywne reguły,
     * usunięcie jest blokowane (HTTP 409).
     *
     * @param phoneNumberId UUID numeru telefonu
     * @param tenantId      UUID tenanta
     * @return {@code true} gdy istnieje co najmniej jedna aktywna reguła
     */
    @Transactional(readOnly = true)
    public boolean existsActiveRulesByPhoneNumberId(UUID phoneNumberId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery(
                        """
                        SELECT COUNT(*) FROM phone_routing_rule
                        WHERE phone_number_id = CAST(:phoneNumberId AS uuid)
                          AND tenant_id = CAST(:tenantId AS uuid)
                          AND is_active = true
                        """)
                .setParameter("phoneNumberId", phoneNumberId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        boolean hasActive = count.longValue() > 0;
        log.debug("[PhoneRoutingRuleRepo] Aktywne reguły dla numeru={}: {}", phoneNumberId, hasActive);
        return hasActive;
    }
}
