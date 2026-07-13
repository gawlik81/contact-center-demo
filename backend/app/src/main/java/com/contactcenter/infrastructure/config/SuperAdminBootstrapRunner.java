package com.contactcenter.infrastructure.config;

import com.contactcenter.domain.user.AppUser;
import com.contactcenter.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Bootstrapuje konto SUPER_ADMIN (globalny administrator platformy, {@code tenant_id = null})
 * przy starcie aplikacji – refaktor ról SUPER_ADMIN/ADMIN/SUPERVISOR/AGENT.
 *
 * <p>Uruchamiany we WSZYSTKICH profilach (dev, prod) – zamyka lukę, w której produkcja
 * nie miała dotąd żadnego automatycznego tworzenia konta administracyjnego (SUPER_ADMIN
 * nie może powstać przez UI/API, patrz {@code UserServiceImpl#createUser} i
 * {@code AdminUserServiceImpl}).
 *
 * <p>{@link ApplicationRunner} jest wywoływany przez Spring Boot PO pełnej inicjalizacji
 * kontekstu aplikacji (w tym po zastosowaniu migracji Flyway, które uruchamiają się
 * podczas {@code context.refresh()}) – nie wymaga jawnego {@code @Order}/{@code @DependsOn}.
 *
 * <p><strong>Logika:</strong>
 * <ul>
 *   <li>Jeśli SUPER_ADMIN już istnieje – no-op (idempotentne, bezpieczne przy każdym
 *       restarcie kontenera).</li>
 *   <li>Jeśli nie istnieje i brakuje {@code SUPER_ADMIN_EMAIL}/{@code SUPER_ADMIN_PASSWORD}:
 *       w profilu {@code dev} – fallback do wartości domyślnych (spójnie z konwencją
 *       {@code V999__dev_seed.sql}); w pozostałych profilach (prod) – fail-fast
 *       ({@link IllegalStateException}), żeby nie tworzyć słabego domyślnego hasła na
 *       produkcji.</li>
 *   <li>Hasło jest hashowane BCrypt(12) przez {@code PasswordEncoder} wewnątrz
 *       {@code UserServiceImpl#createSuperAdminBootstrap} (ten sam bean co w
 *       {@code SecurityConfig}).</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuperAdminBootstrapRunner implements ApplicationRunner {

    /** Fallback dla lokalnego dev, gdy SUPER_ADMIN_EMAIL nie jest ustawiony. */
    private static final String DEV_FALLBACK_EMAIL = "superadmin@contactcenter.dev";

    /**
     * Fallback dla lokalnego dev, gdy SUPER_ADMIN_PASSWORD nie jest ustawiony.
     * Spełnia wymogi siły hasła projektu (min 8 znaków, min 1 cyfra, min 1 wielka litera –
     * patrz {@code AuthServiceImpl#validatePasswordStrength}). NIGDY nie używać w produkcji –
     * profil inny niż dev bez tej zmiennej kończy start aplikacji błędem (fail-fast).
     */
    private static final String DEV_FALLBACK_PASSWORD = "SuperAdmin@2026";

    private final UserService userService;
    private final Environment environment;

    @Value("${super-admin.email:}")
    private String configuredEmail;

    @Value("${super-admin.password:}")
    private String configuredPassword;

    @Override
    public void run(ApplicationArguments args) {
        if (userService.existsSuperAdmin()) {
            log.info("[SuperAdminBootstrap] Konto SUPER_ADMIN już istnieje – bootstrap pominięty (idempotentne).");
            return;
        }

        boolean devProfile = environment.matchesProfiles("dev");
        boolean emailConfigured = StringUtils.hasText(configuredEmail);
        boolean passwordConfigured = StringUtils.hasText(configuredPassword);

        String email;
        String password;

        if (emailConfigured && passwordConfigured) {
            email = configuredEmail;
            password = configuredPassword;
        } else if (devProfile) {
            email = emailConfigured ? configuredEmail : DEV_FALLBACK_EMAIL;
            password = passwordConfigured ? configuredPassword : DEV_FALLBACK_PASSWORD;
            log.warn("[SuperAdminBootstrap] SUPER_ADMIN_EMAIL/SUPER_ADMIN_PASSWORD nie w pełni ustawione – " +
                    "używam fallbacku DEV (email={}). NIGDY nie polegaj na tym fallbacku poza lokalnym developmentem.",
                    email);
        } else {
            throw new IllegalStateException(
                    "Brak wymaganych zmiennych środowiskowych SUPER_ADMIN_EMAIL / SUPER_ADMIN_PASSWORD. " +
                    "Konto SUPER_ADMIN (globalny administrator platformy) musi zostać utworzone przy " +
                    "pierwszym starcie aplikacji poza profilem dev – ustaw obie zmienne i uruchom " +
                    "aplikację ponownie. Nie tworzymy słabego domyślnego hasła na produkcji."
            );
        }

        AppUser created = userService.createSuperAdminBootstrap(email, password);
        log.info("[SuperAdminBootstrap] Utworzono konto SUPER_ADMIN: userId={}, email={}. " +
                "Wymagana zmiana hasła przy pierwszym logowaniu (passwordResetRequired=true).",
                created.getId(), created.getEmail());
    }
}
