package com.contactcenter.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Konfiguracja {@link PasswordEncoder} – wydzielona z {@link SecurityConfig}.
 *
 * <p>Powód wydzielenia: {@code SecurityConfig} ma constructor-injected
 * {@link JwtAuthFilter}, który (przez {@code UserDetailsServiceImpl} →
 * {@code UserService}) tworzy cykl zależności z {@code UserServiceImpl},
 * jeśli ten ostatni wstrzykuje {@link PasswordEncoder} zdefiniowany jako
 * {@code @Bean} wewnątrz {@code SecurityConfig} (utworzenie beana wymagałoby
 * wcześniejszej instancjacji proxy {@code SecurityConfig}, a więc i jego
 * pól, co zamyka cykl).
 *
 * <p>Ta klasa nie ma żadnych zależności na filtry/serwisy domenowe, więc
 * {@link PasswordEncoder} może zostać utworzony niezależnie od
 * {@code SecurityConfig} i przerywa cykl.
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * BCrypt password encoder z 12 rundami.
     *
     * <p>Cost factor 12: ~500ms na hash (skuteczne dla brute-force).
     * Weryfikacja: {@code new BCryptPasswordEncoder(12)}.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
