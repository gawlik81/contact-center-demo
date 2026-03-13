package com.contactcenter.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Konfiguracja Flyway per profil Spring.
 *
 * <p>Profil {@code dev}: standardowe migracje + seed danych testowych.
 * Profil {@code prod}: wyłącznie migracje produkcyjne; clean jest zablokowane.
 *
 * <p>Strategia migracji jest wykonywana automatycznie przy starcie aplikacji.
 * Lokalizacja migracji: {@code classpath:db/migration}.
 * Seed (tylko dev): {@code classpath:db/seed}.
 */
@Slf4j
@Configuration
public class FlywayConfig {

    /**
     * Strategia migracji dla profilu DEV.
     *
     * <p>Wykonuje standardowe migracje Flyway. Seed jest konfigurowany
     * bezpośrednio przez {@code spring.flyway.locations} w application-dev.yml.
     *
     * @return FlywayMigrationStrategy – standardowa migracja z logowaniem
     */
    @Bean
    @Profile("dev")
    public FlywayMigrationStrategy flywayMigrationStrategyDev() {
        return flyway -> {
            log.info("[Flyway][DEV] Uruchamianie migracji bazy danych...");
            log.info("[Flyway][DEV] Lokalizacje: {}", (Object) flyway.getConfiguration().getLocations());
            var result = flyway.migrate();
            log.info("[Flyway][DEV] Migracje zakończone. Zastosowanych: {}, Ignorowanych: {}",
                    result.migrationsExecuted, result.warnings.size());
        };
    }

    /**
     * Strategia migracji dla profilu PROD.
     *
     * <p>Wykonuje standardowe migracje z dodatkową ochroną:
     * <ul>
     *   <li>clean() jest wyłączone (spring.flyway.clean-disabled=true w application-prod.yml)</li>
     *   <li>Walidacja sumy kontrolnej przed migracją</li>
     *   <li>Logowanie wyniku migracji</li>
     * </ul>
     *
     * @return FlywayMigrationStrategy – bezpieczna migracja produkcyjna
     */
    @Bean
    @Profile("prod")
    public FlywayMigrationStrategy flywayMigrationStrategyProd() {
        return flyway -> {
            log.info("[Flyway][PROD] Uruchamianie walidacji i migracji bazy danych...");
            // Walidacja sprawdza czy już zastosowane migracje nie zostały zmodyfikowane
            flyway.validate();
            log.info("[Flyway][PROD] Walidacja migracji: OK");
            var result = flyway.migrate();
            log.info("[Flyway][PROD] Migracje zakończone. Zastosowanych: {}",
                    result.migrationsExecuted);
            if (result.migrationsExecuted > 0) {
                log.info("[Flyway][PROD] Zastosowane migracje: {}",
                        result.migrations.stream()
                                .map(m -> m.version + " - " + m.description)
                                .toList());
            }
        };
    }

    /**
     * Bean informacyjny Flyway – dostępny przez /actuator/flyway.
     * Spring Boot autoconfigures Flyway automatycznie przez application.yml;
     * ten bean jedynie dokumentuje konfigurację.
     */
    @Bean
    public FlywayInfoLogger flywayInfoLogger(Flyway flyway) {
        return new FlywayInfoLogger(flyway);
    }

    /**
     * Komponent logujący informacje o stanie migracji Flyway przy starcie.
     */
    public record FlywayInfoLogger(Flyway flyway) {
        public FlywayInfoLogger {
            var info = flyway.info();
            var current = info.current();
            if (current != null) {
                log.info("[Flyway] Aktualna wersja schematu: {} ({})",
                        current.getVersion(), current.getDescription());
            } else {
                log.info("[Flyway] Baza danych jest pusta – zostaną zastosowane wszystkie migracje");
            }
            log.info("[Flyway] Oczekujące migracje: {}", info.pending().length);
        }
    }
}
