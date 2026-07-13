package com.contactcenter.security;

import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.user.UserService;
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
 *
 * <p><strong>Logowanie globalne (SUPER_ADMIN, refaktor ról):</strong> dla użytkowników
 * bez tenanta (rola {@code SUPER_ADMIN}, {@code tenantId == null}) klucz ma format
 * {@code GLOBAL:email} zamiast {@code tenantId:email} – patrz {@link #GLOBAL_PREFIX} i
 * {@link #buildGlobalKey(String)}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    /** Separator między tenantId a emailem w formacie "username". */
    public static final String TENANT_EMAIL_SEPARATOR = ":";

    /**
     * Prefiks klucza dla logowania globalnego (SUPER_ADMIN, bez tenanta).
     * Zastępuje UUID tenanta w formacie {@code {prefix}:{email}} – odróżnia ścieżkę
     * globalną od tenant-scoped w {@link #loadUserByUsername(String)} PRZED próbą
     * parsowania {@code parts[0]} jako UUID.
     */
    public static final String GLOBAL_PREFIX = "GLOBAL";

    private final UserService userService;

    /**
     * Ładuje użytkownika na podstawie kombinacji tenantId:email lub GLOBAL:email.
     *
     * @param tenantEmailKey klucz w formacie "{tenantId}:{email}" lub "GLOBAL:{email}"
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

        String email = parts[1];

        // Logowanie globalne (SUPER_ADMIN, bez tenanta) – rozpoznawane PRZED próbą
        // sparsowania parts[0] jako UUID, bo GLOBAL_PREFIX nie jest poprawnym UUID.
        if (GLOBAL_PREFIX.equals(parts[0])) {
            AppUser globalUser = userService.findAuthenticatableGlobalUser(email)
                    .orElseThrow(() -> {
                        log.debug("[UserDetails] Użytkownik globalny nie znaleziony lub nieaktywny: email={}", email);
                        return new UsernameNotFoundException("Użytkownik nie znaleziony: " + email);
                    });

            log.debug("[UserDetails] Załadowano użytkownika globalnego: id={}, role={}",
                    globalUser.getId(), globalUser.getRole());

            return new AppUserDetails(globalUser);
        }

        UUID tenantId;
        try {
            tenantId = UUID.fromString(parts[0]);
        } catch (IllegalArgumentException e) {
            log.warn("[UserDetails] Nieprawidłowy UUID tenantId: '{}'", parts[0]);
            throw new UsernameNotFoundException("Nieprawidłowy identyfikator tenanta");
        }

        // Używamy findByTenantIdAndEmailAndActiveTrue – wyklucza nieaktywne i usunięte konta.
        // Nie używamy findByTenantIdAndEmail który ładuje też konta z is_active=false lub is_deleted=true,
        // co umożliwiałoby logowanie na dezaktywowane/usunięte konta (Spring Security weryfikuje
        // isEnabled() ale dopiero po załadowaniu UserDetails – lepiej odrzucać wcześniej).
        AppUser user = userService.findAuthenticatableUser(tenantId, email)
                .orElseThrow(() -> {
                    // Logujemy na DEBUG, nie WARN – żeby nie zdradzać czy konto istnieje (timing attacks)
                    log.debug("[UserDetails] Użytkownik nie znaleziony lub nieaktywny: tenant={}, email={}", tenantId, email);
                    return new UsernameNotFoundException(
                            "Użytkownik nie znaleziony: " + email
                    );
                });

        log.debug("[UserDetails] Załadowano użytkownika: id={}, tenant={}, role={}",
                user.getId(), user.getTenantId(), user.getRole());

        return new AppUserDetails(user);
    }

    /**
     * Buduje klucz tenant:email używany przy ładowaniu UserDetails (ścieżka tenant-scoped).
     */
    public static String buildKey(UUID tenantId, String email) {
        return tenantId.toString() + TENANT_EMAIL_SEPARATOR + email;
    }

    /**
     * Buduje klucz GLOBAL:email używany przy ładowaniu UserDetails dla logowania
     * globalnego (SUPER_ADMIN, bez tenanta).
     */
    public static String buildGlobalKey(String email) {
        return GLOBAL_PREFIX + TENANT_EMAIL_SEPARATOR + email;
    }
}
