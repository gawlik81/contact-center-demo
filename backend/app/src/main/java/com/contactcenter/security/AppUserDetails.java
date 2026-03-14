package com.contactcenter.security;

import com.contactcenter.domain.model.AppUser;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Adapter Spring Security dla encji {@link AppUser}.
 *
 * <p>Opakowuje encję domenową w interfejs {@link UserDetails} wymagany przez Spring Security.
 * Udostępnia dodatkowe pola specyficzne dla aplikacji (tenantId, userId, mfaEnabled).
 *
 * <p>Role mapowane z formatu domenowego na format Spring Security z prefiksem {@code ROLE_}:
 * np. {@code ADMIN} → {@code ROLE_ADMIN} (wymagane przez {@code hasRole()} w Spring Security).
 */
@Getter
public class AppUserDetails implements UserDetails {

    private final UUID userId;
    private final UUID tenantId;
    private final String email;
    private final String passwordHash;
    private final boolean active;
    private final boolean mfaEnabled;
    private final String mfaSecret;
    /** Nazwa roli domenowej (np. "ADMIN", "SUPERVISOR", "AGENT") – bez prefiksu ROLE_. */
    private final String role;
    private final Collection<? extends GrantedAuthority> authorities;

    public AppUserDetails(AppUser user) {
        this.userId       = user.getId();
        this.tenantId     = user.getTenantId();
        this.email        = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        this.active       = user.isActive();
        this.mfaEnabled   = user.isMfaEnabled();
        this.mfaSecret    = user.getMfaSecret();
        this.role         = user.getRole() != null ? user.getRole().name() : null;
        // Spring Security convention: role = ROLE_{ROLE_NAME}
        this.authorities  = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    }

    // =========================================================================
    // UserDetails interface
    // =========================================================================

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    /** Zwraca bcrypt hash hasła (nigdy plain text). */
    @Override
    public String getPassword() {
        return passwordHash;
    }

    /** Zwraca email jako username (identyfikator w Spring Security). */
    @Override
    public String getUsername() {
        return email;
    }

    /** Konto jest aktywne gdy pole {@code is_active=true} i status nie jest INACTIVE. */
    @Override
    public boolean isEnabled() {
        return active;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true; // brak mechanizmu expiry konta w tej wersji
    }

    @Override
    public boolean isAccountNonLocked() {
        return true; // lockout implementowany przez rate limiting (BE-005+)
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true; // password rotation w przyszłych wersjach
    }
}
