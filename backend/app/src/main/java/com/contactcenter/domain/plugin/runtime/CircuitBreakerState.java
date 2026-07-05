package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Circuit breaker per instalacja pluginu — w pamięci JVM (ARCHITECTURE.md §11.7).
 *
 * <p>Brak biblioteki circuit breaker (Resilience4j czy podobnej) w {@code pom.xml} tego
 * projektu (zweryfikowane) — implementacja jest własnym, minimalnym mechanizmem: licznik
 * kolejnych błędów ({@code TIMED_OUT}/{@code FAILED}) per {@code installationId}, próg
 * {@value #FAILURE_THRESHOLD}. Po przekroczeniu progu obwód jest {@code OPEN} — kolejne
 * wywołania tej instalacji są pomijane ({@code CIRCUIT_OPEN}) bez próby wywołania pluginu, aż
 * do najbliższego {@code SUCCESS} ("closed on first success" — bez stanu half-open, zgodnie z
 * zakresem tego ticketu BE-102).
 *
 * <p>Stan w pamięci ({@code ConcurrentHashMap}) jest odbudowywany od zera przy restarcie
 * aplikacji (tak jak {@link PluginRegistry} — komentarz w {@code PluginRegistryImpl}), co jest
 * akceptowalne: instalacja zdegradowana w bazie ({@code health_status=DEGRADED}) pozostaje
 * widoczna w DB między restartami nawet jeśli licznik w pamięci wraca do zera; trwała
 * persystencja licznika w DB po każdym wywołaniu nie jest wymagana przez kryteria akceptacji
 * tego ticketu.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class CircuitBreakerState {

    /** Próg kolejnych błędów, po którym obwód się otwiera (ARCHITECTURE.md §11.7: "default 5"). */
    static final int FAILURE_THRESHOLD = 5;

    private final PluginRegistrationService pluginRegistrationService;

    private final ConcurrentHashMap<UUID, AtomicInteger> consecutiveFailuresByInstallation =
            new ConcurrentHashMap<>();

    /**
     * Sprawdza czy obwód jest aktualnie otwarty (instalacja powinna być pomijana bez próby
     * wywołania).
     *
     * <p>Czyta wyłącznie stan w pamięci — nie odpytuje bazy danych przy każdym wywołaniu (ścieżka
     * krytyczna dla {@code PRE_CONTACT_CONNECT}, budżet 2s).
     *
     * @param installationId instalacja do sprawdzenia
     * @return {@code true} jeśli licznik kolejnych błędów osiągnął {@link #FAILURE_THRESHOLD}
     */
    boolean isOpen(UUID installationId) {
        AtomicInteger counter = consecutiveFailuresByInstallation.get(installationId);
        return counter != null && counter.get() >= FAILURE_THRESHOLD;
    }

    /**
     * Rejestruje wynik wywołania (sukces zamyka obwód i resetuje licznik; błąd/timeout
     * inkrementuje licznik i — po przekroczeniu progu — oznacza instalację jako
     * {@code DEGRADED} w DB).
     *
     * @param tenantId       tenant-właściciel instalacji (do aktualizacji DB przez serwis
     *                       tenant-aware)
     * @param installationId instalacja, której wynik dotyczy
     * @param success         {@code true} dla {@code SUCCESS}, {@code false} dla
     *                        {@code FAILED}/{@code TIMED_OUT}
     */
    void recordResult(UUID tenantId, UUID installationId, boolean success) {
        if (success) {
            AtomicInteger previous = consecutiveFailuresByInstallation.remove(installationId);
            if (previous != null && previous.get() > 0) {
                // Instalacja była zdegradowana (lub akumulowała błędy) — pierwszy sukces
                // zamyka obwód: reset licznika w DB i powrót na HEALTHY.
                pluginRegistrationService.updateHealthStatus(
                        tenantId, installationId, TenantPluginInstallation.HealthStatus.HEALTHY, 0);
                log.info("[CircuitBreaker] Obwód zamknięty po SUCCESS: tenant={}, installation={}, "
                                + "poprzednie kolejne błędy={}",
                        tenantId, installationId, previous.get());
            }
            return;
        }

        AtomicInteger counter = consecutiveFailuresByInstallation.computeIfAbsent(
                installationId, id -> new AtomicInteger(0));
        int failures = counter.incrementAndGet();

        if (failures == FAILURE_THRESHOLD) {
            pluginRegistrationService.updateHealthStatus(
                    tenantId, installationId, TenantPluginInstallation.HealthStatus.DEGRADED, failures);
            log.warn("[CircuitBreaker] Obwód OTWARTY (DEGRADED) po {} kolejnych błędach: "
                            + "tenant={}, installation={}",
                    failures, tenantId, installationId);
        } else if (failures > FAILURE_THRESHOLD) {
            // Obwód już otwarty — kolejne błędy (np. wywołania równoległe tuż przed otwarciem)
            // tylko aktualizują licznik w DB do wglądu administracyjnego, bez ponownego logu warn.
            pluginRegistrationService.updateHealthStatus(
                    tenantId, installationId, TenantPluginInstallation.HealthStatus.DEGRADED, failures);
        } else {
            log.debug("[CircuitBreaker] Błąd zarejestrowany: tenant={}, installation={}, "
                            + "kolejne błędy={}/{}",
                    tenantId, installationId, failures, FAILURE_THRESHOLD);
        }
    }
}
