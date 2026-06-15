package com.contactcenter.domain.phonenumber;

import com.contactcenter.domain.repository.TenantAwareRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium dla encji {@link PhoneNumber}.
 *
 * <p>Rozszerza {@link TenantAwareRepository} – wszystkie zapytania wymagają
 * ustawienia kontekstu RLS przez {@code setTenantContextInDb(tenantId)}.
 *
 * <p>Soft delete: metody odczytu domyślnie filtrują {@code is_deleted = false}.
 */
@Slf4j
@Repository
public class PhoneNumberRepository extends TenantAwareRepository {

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera wszystkie aktywne (nie usunięte) numery telefonu tenanta.
     *
     * @param tenantId UUID tenanta
     * @return lista numerów posortowanych po {@code created_at} ASC
     */
    @Transactional(readOnly = true)
    public List<PhoneNumber> findAllByTenantId(UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<PhoneNumber> results = em.createNativeQuery(
                        """
                        SELECT * FROM phone_number
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND is_deleted = false
                        ORDER BY created_at ASC
                        """,
                        PhoneNumber.class)
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        log.debug("[PhoneNumberRepo] Lista numerów: tenant={}, znaleziono={}", tenantId, results.size());
        return results;
    }

    /**
     * Pobiera numer telefonu po ID z zabezpieczeniem cross-tenant.
     *
     * <p>Zwraca {@code Optional.empty()} gdy numer nie istnieje, należy do innego tenanta
     * lub jest logicznie usunięty.
     *
     * @param phoneNumberId UUID numeru telefonu
     * @param tenantId      UUID tenanta
     * @return Optional z numerem lub empty
     */
    @Transactional(readOnly = true)
    public Optional<PhoneNumber> findById(UUID phoneNumberId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        PhoneNumber phoneNumber = em.find(PhoneNumber.class, phoneNumberId);
        if (phoneNumber == null
                || !phoneNumber.getTenantId().equals(tenantId)
                || phoneNumber.isDeleted()) {
            return Optional.empty();
        }
        return Optional.of(phoneNumber);
    }

    /**
     * Pobiera aktywny (nie usunięty) numer telefonu po wartości numeru i tenantId.
     *
     * <p>Używane przez silnik routingu przychodzących połączeń ({@code IncomingCallRoutingService})
     * do wyszukania konfiguracji numeru na podstawie parametru {@code To} z Twilio.
     *
     * @param number   numer E.164 (np. "+48221234567")
     * @param tenantId UUID tenanta
     * @return Optional z encją lub empty gdy numer nie istnieje / usunięty
     */
    @Transactional(readOnly = true)
    public Optional<PhoneNumber> findByNumberAndTenantId(String number, UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<PhoneNumber> results = em.createNativeQuery(
                        """
                        SELECT * FROM phone_number
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND number = :number
                          AND is_deleted = false
                        LIMIT 1
                        """,
                        PhoneNumber.class)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("number", number)
                .getResultList();

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Sprawdza czy istnieje aktywny (nie usunięty) numer o podanej wartości w obrębie tenanta.
     *
     * <p>Używane do detekcji duplikatów przy tworzeniu.
     *
     * @param tenantId UUID tenanta
     * @param number   numer E.164 do sprawdzenia
     * @return {@code true} gdy numer już istnieje i nie jest usunięty
     */
    @Transactional(readOnly = true)
    public boolean existsByTenantIdAndNumber(UUID tenantId, String number) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery(
                        """
                        SELECT COUNT(*) FROM phone_number
                        WHERE tenant_id = CAST(:tenantId AS uuid)
                          AND number = :number
                          AND is_deleted = false
                        """)
                .setParameter("tenantId", tenantId.toString())
                .setParameter("number", number)
                .getSingleResult();

        return count.longValue() > 0;
    }

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Zapisuje lub aktualizuje numer telefonu.
     *
     * <p>Przed zapisem waliduje przynależność do tenanta ({@code assertSameTenant}).
     *
     * @param phoneNumber encja do zapisu
     * @return zapisana encja (ze wygenerowanym ID jeśli nowa)
     */
    @Transactional
    public PhoneNumber save(PhoneNumber phoneNumber) {
        assertSameTenant(phoneNumber.getTenantId());
        setTenantContextInDb(phoneNumber.getTenantId());

        if (phoneNumber.getPhoneNumberId() == null) {
            em.persist(phoneNumber);
            log.info("[PhoneNumberRepo] Utworzono numer: number='{}', tenant={}",
                    phoneNumber.getNumber(), phoneNumber.getTenantId());
            return phoneNumber;
        }

        PhoneNumber merged = em.merge(phoneNumber);
        log.debug("[PhoneNumberRepo] Zaktualizowano numer: phoneNumberId={}, tenant={}",
                merged.getPhoneNumberId(), merged.getTenantId());
        return merged;
    }
}
