package com.contactcenter.domain.user;

import com.contactcenter.api.user.dto.UserPreferencesDto;
import com.contactcenter.domain.exception.ResourceNotFoundException;

import java.util.UUID;

/**
 * Serwis domenowy zarządzający preferencjami użytkownika (BE-054).
 *
 * <p>Operuje na encji {@link AppUser} — odczyt i zapis pola {@code preferredLanguage}
 * dodanego migracją V050.
 *
 * <p>Bezpieczeństwo:
 * <ul>
 *   <li>Każda operacja filtruje po tenantId – uniemożliwia cross-tenant access</li>
 *   <li>userId pochodzi z JWT (TenantContext) – nie z path variable</li>
 * </ul>
 */
public interface UserPreferencesService {

    /**
     * Zwraca preferencje zalogowanego użytkownika.
     *
     * @param userId   UUID użytkownika (z JWT)
     * @param tenantId UUID tenanta (z JWT)
     * @return DTO z preferencjami użytkownika
     * @throws ResourceNotFoundException gdy użytkownik nie istnieje lub należy do innego tenanta
     */
    UserPreferencesDto getPreferences(UUID userId, UUID tenantId);

    /**
     * Aktualizuje preferencje zalogowanego użytkownika.
     *
     * @param userId   UUID użytkownika (z JWT)
     * @param tenantId UUID tenanta (z JWT)
     * @param dto      nowe preferencje (walidacja @Pattern po stronie kontrolera)
     * @return DTO ze zaktualizowanymi preferencjami
     * @throws ResourceNotFoundException gdy użytkownik nie istnieje lub należy do innego tenanta
     */
    UserPreferencesDto updatePreferences(UUID userId, UUID tenantId, UserPreferencesDto dto);
}
