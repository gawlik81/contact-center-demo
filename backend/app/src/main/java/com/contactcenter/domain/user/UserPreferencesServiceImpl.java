package com.contactcenter.domain.user;

import com.contactcenter.api.user.dto.UserPreferencesDto;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Implementacja {@link UserPreferencesService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class UserPreferencesServiceImpl implements UserPreferencesService {

    private final AppUserRepository appUserRepository;

    // =========================================================================
    // Odczyt preferencji
    // =========================================================================

    /**
     * Zwraca preferencje zalogowanego użytkownika.
     *
     * @param userId   UUID użytkownika (z JWT)
     * @param tenantId UUID tenanta (z JWT)
     * @return DTO z preferencjami użytkownika
     * @throws ResourceNotFoundException gdy użytkownik nie istnieje lub należy do innego tenanta
     */
    @Override
    @Transactional(readOnly = true)
    public UserPreferencesDto getPreferences(UUID userId, UUID tenantId) {
        AppUser user = findUserOrThrow(userId, tenantId);
        log.debug("[UserPreferencesService] getPreferences: userId={}, tenantId={}, lang={}",
                userId, tenantId, user.getPreferredLanguage());
        return toDto(user);
    }

    // =========================================================================
    // Aktualizacja preferencji
    // =========================================================================

    /**
     * Aktualizuje preferencje zalogowanego użytkownika.
     *
     * @param userId   UUID użytkownika (z JWT)
     * @param tenantId UUID tenanta (z JWT)
     * @param dto      nowe preferencje (walidacja @Pattern po stronie kontrolera)
     * @return DTO ze zaktualizowanymi preferencjami
     * @throws ResourceNotFoundException gdy użytkownik nie istnieje lub należy do innego tenanta
     */
    @Override
    @Transactional
    public UserPreferencesDto updatePreferences(UUID userId, UUID tenantId, UserPreferencesDto dto) {
        AppUser user = findUserOrThrow(userId, tenantId);

        String previousLang = user.getPreferredLanguage();
        user.setPreferredLanguage(dto.preferredLanguage());
        AppUser saved = appUserRepository.save(user);

        log.info("[UserPreferencesService] updatePreferences: userId={}, tenantId={}, lang: {} → {}",
                userId, tenantId, previousLang, saved.getPreferredLanguage());

        return toDto(saved);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private AppUser findUserOrThrow(UUID userId, UUID tenantId) {
        return appUserRepository.findByIdAndTenantIdAndDeletedFalse(userId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Użytkownik nie istnieje: userId=" + userId + ", tenantId=" + tenantId));
    }

    private UserPreferencesDto toDto(AppUser user) {
        return new UserPreferencesDto(user.getPreferredLanguage());
    }
}
