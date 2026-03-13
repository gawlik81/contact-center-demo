package com.contactcenter.security;

import com.contactcenter.domain.model.AppUser;
import com.contactcenter.domain.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Implementacja {@link UserDetailsService} ładująca użytkowników z bazy danych.
 *
 * <p>Spring Security wywołuje {@link #loadUserByUsername} podczas procesu autentykacji
 * (przez {@code AuthenticationManager / DaoAuthenticationProvider}).
 *
 * <p>Multi-tenancy: username w tym serwisie to {@code tenantId:email} (separator ":").
 * Format ten jest wymagany, bo email jest unikalny tylko w obrębie tenanta.
 *
 * <p>Przykład: {@code "550e8400-e29b-41d4-a716-446655440000:jan.kowalski@firma.pl"}
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    /** Separator między tenantId a emailem w formacie "username". */
    public static final String TENANT_EMAIL_SEPARATOR = ":";

    private final AppUserRepository appUserRepository;

    /**
     * Ładuje użytkownika na podstawie kombinacji tenantId:email.
     *
     * @param tenantEmailKey klucz w formacie "{tenantId}:{email}"
     * @return {@link AppUserDetails} z danymi użytkownika
     * @throws UsernameNotFoundException gdy użytkownik nie istnieje lub jest nieaktywny
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String tenantEmailKey) throws UsernameNotFoundException {
        String[] parts = tenantEmailKey.split(TENANT_EMAIL_SEPARATOR, 2);
        if (parts.length != 2) {
            log.warn("[UserDetails] Nieprawidłowy format klucza tenant:email: '{}'", tenantEmailKey);
            throw new UsernameNotFoundException("Nieprawidłowy format identyfikatora użytkownika");
        }

        UUID tenantId;
        try {
            tenantId = UUID.fromString(parts[0]);
        } catch (IllegalArgumentException e) {
            log.warn("[UserDetails] Nieprawidłowy UUID tenantId: '{}'", parts[0]);
            throw new UsernameNotFoundException("Nieprawidłowy identyfikator tenanta");
        }

        String email = parts[1];

        AppUser user = appUserRepository.findByTenantIdAndEmail(tenantId, email)
                .orElseThrow(() -> {
                    // Logujemy na DEBUG, nie WARN – żeby nie zdradzać czy konto istnieje (timing attacks)
                    log.debug("[UserDetails] Użytkownik nie znaleziony: tenant={}, email={}", tenantId, email);
                    return new UsernameNotFoundException(
                            "Użytkownik nie znaleziony: " + email
                    );
                });

        log.debug("[UserDetails] Załadowano użytkownika: id={}, tenant={}, role={}",
                user.getId(), user.getTenantId(), user.getRole());

        return new AppUserDetails(user);
    }

    /**
     * Buduje klucz tenant:email używany przy ładowaniu UserDetails.
     */
    public static String buildKey(UUID tenantId, String email) {
        return tenantId.toString() + TENANT_EMAIL_SEPARATOR + email;
    }
}
