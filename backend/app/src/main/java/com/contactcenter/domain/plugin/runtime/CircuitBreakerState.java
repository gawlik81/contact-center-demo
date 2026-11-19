package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
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
 * <p><strong>Wiele instancji (BE-178) — decyzja: licznik LOKALNY + propagacja stanu OPEN/CLOSED.</strong>
 * Ścieżka krytyczna ({@link #isOpen}, budżet {@code PRE_CONTACT_CONNECT} 2 s) nie woła Redisa: czyta licznik lokalny
 * i zbiór obwodów otwartych przez INNE instancje. Instancja, której licznik osiągnie próg, publikuje
 * {@code CIRCUIT_OPENED} ({@link PluginSyncBus}); odbiorcy otwierają obwód lokalnie (zbiór {@code remoteOpen}).
 * Zamknięcie (pierwszy {@code SUCCESS} po otwarciu) publikuje {@code CIRCUIT_CLOSED}; zmiana instalacji przez
 * administratora ({@code INSTALLATION_CHANGED}) resetuje obwód na wszystkich instancjach ({@link #forget}).
 * Dlaczego nie współdzielony licznik w Redisie: dodałby round-trip Redisa do każdego wywołania (także udanego -
 * reset licznika), a przy awarii Redisa i tak musiałby spaść do licznika lokalnego; próg "5 kolejnych błędów" jest
 * heurystyką bezpieczeństwa, nie umową - otwarcie po 5 błędach NA JEDNEJ instancji i propagacja daje ten sam efekt
 * ochronny (najwyżej N x 5 błędów zanim obwód zamknie się wszędzie - w praktyce 5, bo błędy trafiają na wszystkie
 * instancje równolegle). Utrata zdarzenia nie psuje stanu: instancja otworzy obwód sama po własnych 5 błędach.
 *
 * <p><strong>Stan NIE jest odtwarzany z DB</strong> ({@code health_status=DEGRADED}), bo obwód nigdy nie zamyka się
 * sam (brak half-open: otwarty obwód nie wykonuje wywołań, więc nie ma "pierwszego sukcesu") - gdyby start
 * instancji adoptował DEGRADED, restart przestałby być sposobem na odblokowanie wtyczki. UWAGA: dla FLOTY restart
 * jednej instancji nie odblokowuje pozostałych (obwód otwarty zdalnie zamyka tylko {@code CIRCUIT_CLOSED} albo
 * zmiana instalacji przez administratora - {@link #forget}); half-open/TTL to follow-up BE-178.
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
class CircuitBreakerState {

    /** Próg kolejnych błędów, po którym obwód się otwiera (ARCHITECTURE.md §11.7: "default 5"). */
    static final int FAILURE_THRESHOLD = 5;

    private final PluginRegistrationService pluginRegistrationService;

    /** Transport zdarzeń między instancjami; {@code null} = tylko stan lokalny (testy jednostkowe). */
    private final PluginSyncBus syncBus;

    private final ConcurrentHashMap<UUID, AtomicInteger> consecutiveFailuresByInstallation =
            new ConcurrentHashMap<>();

    /** Instalacje, których obwód otworzyła INNA instancja (zdarzenie {@code CIRCUIT_OPENED}). */
    private final Set<UUID> remoteOpen = ConcurrentHashMap.newKeySet();

    @Autowired
    CircuitBreakerState(PluginRegistrationService pluginRegistrationService, PluginSyncBus syncBus) {
        this.pluginRegistrationService = pluginRegistrationService;
        this.syncBus = syncBus;
    }

    /** Wygodny konstruktor bez propagacji między instancjami (testy jednostkowe). */
    CircuitBreakerState(PluginRegistrationService pluginRegistrationService) {
        this(pluginRegistrationService, null);
    }

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
        return (counter != null && counter.get() >= FAILURE_THRESHOLD) || remoteOpen.contains(installationId);
    }

    /**
     * Stosuje zdarzenie z innej instancji ({@code CIRCUIT_OPENED}/{@code CIRCUIT_CLOSED}). Nie publikuje dalej
     * (brak pętli) i nie dotyka DB - zapis {@code DEGRADED}/{@code HEALTHY} wykonała instancja źródłowa.
     */
    void applyRemote(PluginSyncEnvelope envelope) {
        UUID installationId = envelope.installationId();
        if (envelope.type() == PluginSyncEnvelope.Type.CIRCUIT_OPENED) {
            if (remoteOpen.add(installationId)) {
                log.warn("[CircuitBreaker] Obwód OTWARTY zdalnie (instancja {}): tenant={}, installation={}",
                        envelope.src(), envelope.tenantId(), installationId);
            }
        } else if (envelope.type() == PluginSyncEnvelope.Type.CIRCUIT_CLOSED) {
            forget(installationId);
            log.info("[CircuitBreaker] Obwód ZAMKNIĘTY zdalnie (instancja {}): tenant={}, installation={}",
                    envelope.src(), envelope.tenantId(), installationId);
        }
    }

    /** Resetuje stan lokalny instalacji (licznik + obwód otwarty zdalnie) - zmiana instalacji przez administratora. */
    void forget(UUID installationId) {
        consecutiveFailuresByInstallation.remove(installationId);
        remoteOpen.remove(installationId);
    }

    private void publish(PluginSyncEnvelope.Type type, UUID tenantId, UUID installationId) {
        if (syncBus != null) {
            syncBus.publish(type, tenantId, installationId, null);
        }
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
            boolean wasOpen = remoteOpen.remove(installationId)
                    || (previous != null && previous.get() >= FAILURE_THRESHOLD);
            if (wasOpen) {
                publish(PluginSyncEnvelope.Type.CIRCUIT_CLOSED, tenantId, installationId);
            }
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
            publish(PluginSyncEnvelope.Type.CIRCUIT_OPENED, tenantId, installationId);
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
