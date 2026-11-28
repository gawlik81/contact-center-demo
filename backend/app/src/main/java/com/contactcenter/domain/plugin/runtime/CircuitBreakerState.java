package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Circuit breaker per instalacja pluginu — w pamięci JVM (ARCHITECTURE.md §11.7).
 *
 * <p>Brak biblioteki circuit breaker (Resilience4j czy podobnej) w {@code pom.xml} tego
 * projektu (zweryfikowane) — implementacja jest własnym, minimalnym mechanizmem: licznik
 * kolejnych błędów ({@code TIMED_OUT}/{@code FAILED}) per {@code installationId}, próg
 * {@value #FAILURE_THRESHOLD}. Po przekroczeniu progu obwód jest {@code OPEN} — kolejne
 * wywołania tej instalacji są pomijane ({@code CIRCUIT_OPEN}) bez próby wywołania pluginu, aż
 * do upływu TTL i udanej próby half-open (patrz niżej; w BE-102 obwód zamykał się dopiero po {@code SUCCESS}
 * bez half-open).
 *
 * <p><strong>Wiele instancji (BE-178) — decyzja: licznik LOKALNY + propagacja stanu OPEN/CLOSED.</strong>
 * Ścieżka krytyczna ({@link #tryAcquire}, budżet {@code PRE_CONTACT_CONNECT} 2 s) nie woła Redisa: czyta licznik lokalny
 * i zbiór obwodów otwartych przez INNE instancje. Instancja, której licznik osiągnie próg, publikuje
 * {@code CIRCUIT_OPENED} ({@link PluginSyncBus}); odbiorcy otwierają obwód lokalnie (ta sama bramka {@code gates}, z własnym TTL od odebrania zdarzenia).
 * Zamknięcie (udana próba half-open albo pierwszy {@code SUCCESS} po otwarciu) publikuje {@code CIRCUIT_CLOSED}; zmiana instalacji przez
 * administratora ({@code INSTALLATION_CHANGED}) resetuje obwód na wszystkich instancjach ({@link #forget}).
 * Dlaczego nie współdzielony licznik w Redisie: dodałby round-trip Redisa do każdego wywołania (także udanego -
 * reset licznika), a przy awarii Redisa i tak musiałby spaść do licznika lokalnego; próg "5 kolejnych błędów" jest
 * heurystyką bezpieczeństwa, nie umową - otwarcie po 5 błędach NA JEDNEJ instancji i propagacja daje ten sam efekt
 * ochronny (najwyżej N x 5 błędów zanim obwód zamknie się wszędzie - w praktyce 5, bo błędy trafiają na wszystkie
 * instancje równolegle). Utrata zdarzenia nie psuje stanu: instancja otworzy obwód sama po własnych 5 błędach.
 *
 * <p><strong>Half-open / TTL (BE-246).</strong> Otwarty obwód (lokalny ALBO otwarty zdalnie) sam przechodzi w stan
 * half-open po {@code plugins.circuit-breaker.open-ttl} (domyślnie 60 s, liczone od otwarcia lokalnego albo od
 * ostatniego zdarzenia {@code CIRCUIT_OPENED}): wtedy {@link #tryAcquire} przepuszcza DOKŁADNIE JEDNO próbne wywołanie
 * naraz na instancję (CAS na dzierżawie próby; reszta nadal jest pomijana) i zwraca mu {@link Permit} z tokenem próby.
 * Odczyt bez efektu ubocznego to {@link #isOpenReadOnly}. Wynik próby ({@link #recordResult(Permit, UUID, boolean)}):
 * sukces zamyka obwód, resetuje licznik, zapisuje {@code HEALTHY} w DB i publikuje {@code CIRCUIT_CLOSED};
 * porażka otwiera obwód ponownie (nowy TTL) i publikuje {@code CIRCUIT_OPENED}, więc pozostałe instancje też
 * odnawiają swój TTL. Dzierżawa próby, która nie doczekała się wyniku (np. wyjątek poza ścieżką rejestrującą wynik),
 * jest zwalniana przez wołającego w {@code finally} ({@link #release}); gdyby i to zawiodło, wygasa po kolejnym TTL -
 * obwód nie może zostać zablokowany na stałe. Wynik liczy się WYŁĄCZNIE wywołaniu z tokenem próby: wynik starego wywołania
 * (rozpoczętego przed otwarciem obwodu) nie zamyka ani nie otwiera ponownie obwodu - brak flappingu. TTL każdego
 * otwarcia ma jitter (domyślnie ±10%), więc instancje otwarte jednym zdarzeniem nie próbują w tej samej milisekundzie.
 * Każda instancja ma własną dzierżawę: po TTL idzie co najwyżej N równoległych prób (N = liczba instancji), nie jedna globalna. Half-open NIE dotyczy {@code REVOKED}
 * (kill switch wersji wyładowuje wtyczkę z runtime, a nie otwiera obwód) ani wyłączenia instalacji.
 *
 * <p><strong>Stan NIE jest odtwarzany z DB</strong> ({@code health_status=DEGRADED}) - decyzja właściciela (tura 80,
 * potwierdzona w turze 83): DB jest tylko widokiem administracyjnym, a po restarcie instancji obwód startuje jako
 * zamknięty i - jeśli wtyczka nadal zawodzi - otworzy się po własnych {@value #FAILURE_THRESHOLD} błędach.
 * Dzięki half-open obwód otwarty ZDALNIE (zdarzeniem z innej instancji) też nie jest już "do restartu": zamyka go
 * udana próba na dowolnej instancji ({@code CIRCUIT_CLOSED}), zmiana instalacji przez administratora ({@link #forget})
 * albo upływ TTL + udana próba lokalna.
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
    static final int FAILURE_THRESHOLD = TenantPluginInstallation.CIRCUIT_FAILURE_THRESHOLD;

    /** Domyślny czas otwarcia obwodu przed próbą half-open (BE-246, decyzja właściciela 2026-11-28). */
    static final Duration DEFAULT_OPEN_TTL = Duration.ofSeconds(60);

    /** Domyślny jitter TTL (±10%) - rozprasza próby instancji otwartych jednym zdarzeniem. */
    static final double DEFAULT_TTL_JITTER = 0.10;

    /**
     * Wynik {@link #tryAcquire}: zezwolenie na wywołanie pluginu. {@code CLOSED} - obwód zamknięty (zwykłe wywołanie),
     * {@code PROBE} - jedyna próba half-open (niesie token dzierżawy), {@code DENIED} - wywołanie należy pominąć.
     * Wołający, który dostał zezwolenie, MUSI zarejestrować wynik ({@link #recordResult(Permit, UUID, boolean)}) albo
     * zwolnić próbę ({@link #release}) w {@code finally}.
     */
    record Permit(UUID installationId, Kind kind, long probeToken) {
        enum Kind { CLOSED, PROBE, DENIED }

        boolean allowed() {
            return kind != Kind.DENIED;
        }

        boolean isProbe() {
            return kind == Kind.PROBE;
        }
    }

    private record Lease(long token, long startedNanos) {
    }

    /**
     * Otwarta bramka instalacji (obwód OPEN, lokalny lub zdalny). {@code openedAtNanos} - początek TTL,
     * {@code ttlNanos} - TTL tego otwarcia (z jitterem); {@code lease} - dzierżawa jedynej próby half-open
     * ({@code null} = nikt nie próbuje).
     */
    private static final class Gate {
        volatile long openedAtNanos;
        volatile long ttlNanos;
        final AtomicReference<Lease> lease = new AtomicReference<>();

        Gate(long openedAtNanos, long ttlNanos) {
            this.openedAtNanos = openedAtNanos;
            this.ttlNanos = ttlNanos;
        }
    }

    private final PluginRegistrationService pluginRegistrationService;

    /** Transport zdarzeń między instancjami; {@code null} = tylko stan lokalny (testy jednostkowe). */
    private final PluginSyncBus syncBus;

    private final long openTtlNanos;
    private final double ttlJitter;
    private final LongSupplier nanoClock;
    private final AtomicLong probeTokens = new AtomicLong();

    private final ConcurrentHashMap<UUID, AtomicInteger> consecutiveFailuresByInstallation =
            new ConcurrentHashMap<>();

    /** Obwody otwarte (przez tę instancję albo - zdarzeniem {@code CIRCUIT_OPENED} - przez INNĄ). */
    private final ConcurrentHashMap<UUID, Gate> gates = new ConcurrentHashMap<>();

    @Autowired
    CircuitBreakerState(PluginRegistrationService pluginRegistrationService, PluginSyncBus syncBus,
                        @Value("${plugins.circuit-breaker.open-ttl:60s}") String openTtl,
                        @Value("${plugins.circuit-breaker.open-ttl-jitter:0.1}") double ttlJitter) {
        this(pluginRegistrationService, syncBus, parseTtl(openTtl), System::nanoTime, ttlJitter);
    }

    /** TTL jako tekst i domyślny jitter (testy jednostkowe). */
    CircuitBreakerState(PluginRegistrationService pluginRegistrationService, PluginSyncBus syncBus, String openTtl) {
        this(pluginRegistrationService, syncBus, parseTtl(openTtl), System::nanoTime, DEFAULT_TTL_JITTER);
    }

    /** Deterministyczny konstruktor testowy: wstrzykiwany zegar, BEZ jittera. */
    CircuitBreakerState(PluginRegistrationService pluginRegistrationService, PluginSyncBus syncBus,
                        Duration openTtl, LongSupplier nanoClock) {
        this(pluginRegistrationService, syncBus, openTtl, nanoClock, 0.0);
    }

    CircuitBreakerState(PluginRegistrationService pluginRegistrationService, PluginSyncBus syncBus,
                        Duration openTtl, LongSupplier nanoClock, double ttlJitter) {
        if (ttlJitter < 0 || ttlJitter >= 1) {
            throw new IllegalArgumentException("plugins.circuit-breaker.open-ttl-jitter musi być w zakresie [0, 1): "
                    + ttlJitter);
        }
        this.pluginRegistrationService = pluginRegistrationService;
        this.syncBus = syncBus;
        this.openTtlNanos = Math.max(1, openTtl.toNanos());
        this.ttlJitter = ttlJitter;
        this.nanoClock = nanoClock;
    }

    /** Wygodny konstruktor bez propagacji między instancjami (testy jednostkowe). */
    CircuitBreakerState(PluginRegistrationService pluginRegistrationService) {
        this(pluginRegistrationService, null, DEFAULT_OPEN_TTL, System::nanoTime, DEFAULT_TTL_JITTER);
    }

    /** Konstruktor z propagacją i domyślnym TTL (testy jednostkowe). */
    CircuitBreakerState(PluginRegistrationService pluginRegistrationService, PluginSyncBus syncBus) {
        this(pluginRegistrationService, syncBus, DEFAULT_OPEN_TTL, System::nanoTime, DEFAULT_TTL_JITTER);
    }

    private static Duration parseTtl(String value) {
        Duration ttl = DurationStyle.detectAndParse(value.trim());
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("plugins.circuit-breaker.open-ttl musi być dodatnie: " + value);
        }
        return ttl;
    }

    /** TTL jednego otwarcia: bazowy TTL +/- jitter (losowo, niezależnie dla każdego otwarcia/odnowienia). */
    private long nextTtlNanos() {
        if (ttlJitter == 0.0) {
            return openTtlNanos;
        }
        double factor = 1.0 + ThreadLocalRandom.current().nextDouble(-ttlJitter, ttlJitter);
        return Math.max(1, (long) (openTtlNanos * factor));
    }

    /**
     * Odczyt BEZ efektu ubocznego: {@code true} gdy obwód instalacji jest otwarty (także gdy TTL minął i czeka na
     * próbę). Do health/UI/metryk; NIE wolno nim zastępować {@link #tryAcquire} przed wywołaniem pluginu.
     */
    boolean isOpenReadOnly(UUID installationId) {
        return gates.containsKey(installationId);
    }

    /**
     * Decyzja przed wywołaniem pluginu. Czyta wyłącznie stan w pamięci (ścieżka krytyczna
     * {@code PRE_CONTACT_CONNECT}, budżet 2 s).
     *
     * <p>Obwód zamknięty: {@code CLOSED}. Otwarty i TTL nie minął: {@code DENIED}. Otwarty dłużej niż TTL: JEDNO
     * wywołanie naraz dostaje {@code PROBE} z unikalnym tokenem (dzierżawa), pozostałe {@code DENIED}.
     */
    Permit tryAcquire(UUID installationId) {
        Gate gate = gates.get(installationId);
        if (gate == null) {
            return new Permit(installationId, Permit.Kind.CLOSED, 0L);
        }
        long now = nanoClock.getAsLong();
        if (now - gate.openedAtNanos < gate.ttlNanos) {
            return denied(installationId);
        }
        Lease current = gate.lease.get();
        if (current != null && now - current.startedNanos() < gate.ttlNanos) {
            return denied(installationId);
        }
        Lease mine = new Lease(probeTokens.incrementAndGet(), now);
        if (gate.lease.compareAndSet(current, mine)) {
            log.info("[CircuitBreaker] Obwód HALF-OPEN: próbne wywołanie, installation={}", installationId);
            return new Permit(installationId, Permit.Kind.PROBE, mine.token());
        }
        return denied(installationId);
    }

    private static Permit denied(UUID installationId) {
        return new Permit(installationId, Permit.Kind.DENIED, 0L);
    }

    /**
     * Zwalnia dzierżawę próby, której wywołanie zakończyło się bez zarejestrowanego wyniku (niezłapany wyjątek,
     * anulowanie). Następna próba może ruszyć od razu. Idempotentne; dla zezwolenia innego niż {@code PROBE} - no-op.
     */
    void release(Permit permit) {
        if (permit == null || !permit.isProbe()) {
            return;
        }
        Gate gate = gates.get(permit.installationId());
        if (gate == null) {
            return;
        }
        Lease current = gate.lease.get();
        if (current != null && current.token() == permit.probeToken() && gate.lease.compareAndSet(current, null)) {
            log.warn("[CircuitBreaker] Próba half-open zwolniona bez wyniku (wyjątek/anulowanie): installation={}",
                    permit.installationId());
        }
    }

    /**
     * Stosuje zdarzenie z innej instancji ({@code CIRCUIT_OPENED}/{@code CIRCUIT_CLOSED}). Nie publikuje dalej
     * (brak pętli) i nie dotyka DB - zapis {@code DEGRADED}/{@code HEALTHY} wykonała instancja źródłowa.
     * {@code CIRCUIT_OPENED} (także ponowne - po nieudanej próbie na innej instancji) odnawia lokalny TTL.
     *
     * <p>Wołający ({@code PluginRuntimeSyncServiceImpl}) przepuszcza tu wyłącznie zdarzenia instalacji ZAŁADOWANYCH
     * lokalnie, więc zdarzenie o nieznanej instalacji nie tworzy bramki.
     */
    void applyRemote(PluginSyncEnvelope envelope) {
        UUID installationId = envelope.installationId();
        if (envelope.type() == PluginSyncEnvelope.Type.CIRCUIT_OPENED) {
            long now = nanoClock.getAsLong();
            Gate gate = gates.get(installationId);
            if (gate == null) {
                gates.putIfAbsent(installationId, new Gate(now, nextTtlNanos()));
                log.warn("[CircuitBreaker] Obwód OTWARTY zdalnie (instancja {}): tenant={}, installation={}",
                        envelope.src(), envelope.tenantId(), installationId);
            } else {
                gate.ttlNanos = nextTtlNanos();
                gate.openedAtNanos = now; // próba na innej instancji zawiodła: odnów TTL
            }
        } else if (envelope.type() == PluginSyncEnvelope.Type.CIRCUIT_CLOSED) {
            forget(installationId);
            log.info("[CircuitBreaker] Obwód ZAMKNIĘTY zdalnie (instancja {}): tenant={}, installation={}",
                    envelope.src(), envelope.tenantId(), installationId);
        }
    }

    /** Resetuje stan lokalny instalacji (licznik + obwód otwarty) - zmiana instalacji przez administratora. */
    void forget(UUID installationId) {
        consecutiveFailuresByInstallation.remove(installationId);
        gates.remove(installationId);
    }

    /**
     * Sprząta stan instalacji, które nie są już załadowane na tej instancji (wyładowane poza ścieżkami wołającymi
     * {@link #forget}, np. odwołanie wersji). Wołane z okresowej rekoncyliacji.
     */
    void retainOnly(java.util.function.Predicate<UUID> loadedLocally) {
        gates.keySet().removeIf(id -> !loadedLocally.test(id));
        consecutiveFailuresByInstallation.keySet().removeIf(id -> !loadedLocally.test(id));
    }

    private void publish(PluginSyncEnvelope.Type type, UUID tenantId, UUID installationId) {
        if (syncBus != null) {
            syncBus.publish(type, tenantId, installationId, null);
        }
    }

    /**
     * Rejestruje wynik wywołania zwykłego (obwód zamknięty w chwili {@link #tryAcquire}) - skrót dla
     * {@link #recordResult(Permit, UUID, boolean)} z zezwoleniem {@code CLOSED}.
     */
    void recordResult(UUID tenantId, UUID installationId, boolean success) {
        recordResult(new Permit(installationId, Permit.Kind.CLOSED, 0L), tenantId, success);
    }

    /**
     * Rejestruje wynik wywołania, które dostało {@code permit}.
     * <ul>
     *   <li>{@code PROBE}: sukces zamyka obwód (reset licznika, {@code HEALTHY} w DB, {@code CIRCUIT_CLOSED});
     *       porażka otwiera go ponownie z nowym TTL ({@code DEGRADED}, {@code CIRCUIT_OPENED}). Wynik próby, której
     *       dzierżawa już nie obowiązuje (wygasła, obwód zresetowany/otwarty na nowo), jest IGNOROWANY.</li>
     *   <li>{@code CLOSED}: sukces resetuje licznik błędów (a gdy akumulował błędy - {@code HEALTHY}); porażka
     *       inkrementuje licznik i po progu otwiera obwód. Gdy obwód jest już otwarty (wynik starego wywołania, które
     *       zaczęło się przed otwarciem), wynik NIE zmienia stanu obwodu - ani nie zamyka go, ani nie odnawia TTL.</li>
     *   <li>{@code DENIED}: wywołanie nie nastąpiło - no-op.</li>
     * </ul>
     */
    void recordResult(Permit permit, UUID tenantId, boolean success) {
        UUID installationId = permit.installationId();
        switch (permit.kind()) {
            case DENIED -> { }
            case PROBE -> recordProbeResult(permit, tenantId, success);
            case CLOSED -> recordClosedResult(tenantId, installationId, success);
        }
    }

    private void recordProbeResult(Permit permit, UUID tenantId, boolean success) {
        UUID installationId = permit.installationId();
        Gate gate = gates.get(installationId);
        Lease lease = gate == null ? null : gate.lease.get();
        if (gate == null || lease == null || lease.token() != permit.probeToken()) {
            log.debug("[CircuitBreaker] Wynik próby half-open zignorowany (dzierżawa nieaktualna): installation={}",
                    installationId);
            return;
        }
        if (success) {
            if (!gates.remove(installationId, gate)) {
                return; // obwód zresetowany w międzyczasie
            }
            AtomicInteger previous = consecutiveFailuresByInstallation.remove(installationId);
            publish(PluginSyncEnvelope.Type.CIRCUIT_CLOSED, tenantId, installationId);
            persistRecovered(tenantId, installationId);
            log.info("[CircuitBreaker] Obwód zamknięty po udanej próbie half-open: tenant={}, installation={}, "
                    + "poprzednie kolejne błędy={}", tenantId, installationId, previous == null ? 0 : previous.get());
            return;
        }
        // porażka próby: otwórz ponownie (nowy TTL; kolejna próba dopiero po nim) i powiadom pozostałe instancje
        int failures = consecutiveFailuresByInstallation
                .computeIfAbsent(installationId, id -> new AtomicInteger(0)).incrementAndGet();
        // CAS on the lease FIRST: a gate that was reset/replaced meanwhile must not be touched (BE-246 N-4)
        if (!gate.lease.compareAndSet(lease, null)) {
            return;
        }
        gate.ttlNanos = nextTtlNanos();
        gate.openedAtNanos = nanoClock.getAsLong();
        log.warn("[CircuitBreaker] Próba half-open NIEUDANA - obwód OTWARTY ponownie: tenant={}, installation={}",
                tenantId, installationId);
        publish(PluginSyncEnvelope.Type.CIRCUIT_OPENED, tenantId, installationId);
        persistDegraded(tenantId, installationId, Math.max(failures, FAILURE_THRESHOLD));
    }

    /**
     * Zapis {@code DEGRADED} obwodu do DB - best-effort (BE-246 R-A): stan w pamięci jest już zmieniony, a zdarzenie
     * opublikowane PRZED zapisem; wyjątek DB nie może zgubić wyniku wtyczki ani wyjść z listenera (duplikat wywołania).
     * Zapisuje {@code degraded_reason = CIRCUIT_OPEN} (V153); licznik jest tylko licznikiem.
     */
    private void persistDegraded(UUID tenantId, UUID installationId, int failures) {
        try {
            pluginRegistrationService.markDegradedByCircuit(tenantId, installationId, failures);
        } catch (RuntimeException e) {
            log.warn("[CircuitBreaker] Nie udało się zapisać DEGRADED w DB (installation={}): {}",
                    installationId, e.toString());
        }
    }

    /**
     * Powrót na {@code HEALTHY} (BE-245 W2: CAS - zdejmuje wyłącznie {@code DEGRADED} obwodu, nie ten po porażce
     * konfiguracji) - best-effort jak {@link #persistDegraded}.
     */
    private void persistRecovered(UUID tenantId, UUID installationId) {
        try {
            pluginRegistrationService.clearCircuitDegraded(tenantId, installationId);
        } catch (RuntimeException e) {
            log.warn("[CircuitBreaker] Nie udało się zapisać HEALTHY w DB (installation={}): {}",
                    installationId, e.toString());
        }
    }

    private void recordClosedResult(UUID tenantId, UUID installationId, boolean success) {
        if (success) {
            AtomicInteger previous = consecutiveFailuresByInstallation.remove(installationId);
            if (gates.containsKey(installationId)) {
                // stary wynik (wywołanie sprzed otwarcia obwodu): zamknąć obwód może tylko udana próba half-open
                return;
            }
            if (previous != null && previous.get() > 0) {
                // instalacja akumulowała błędy - udany wynik resetuje licznik w DB i wraca na HEALTHY
                persistRecovered(tenantId, installationId);
                log.info("[CircuitBreaker] Licznik błędów zresetowany po SUCCESS: tenant={}, installation={}, "
                        + "poprzednie kolejne błędy={}", tenantId, installationId, previous.get());
            }
            return;
        }

        AtomicInteger counter = consecutiveFailuresByInstallation.computeIfAbsent(
                installationId, id -> new AtomicInteger(0));
        int failures = counter.incrementAndGet();

        if (gates.containsKey(installationId)) {
            // stara porażka przy już otwartym obwodzie: nie odnawiamy TTL (to robi wyłącznie nieudana próba)
            log.debug("[CircuitBreaker] Porażka starego wywołania przy otwartym obwodzie: installation={}",
                    installationId);
            return;
        }
        if (failures >= FAILURE_THRESHOLD) {
            boolean opened = gates.putIfAbsent(installationId, new Gate(nanoClock.getAsLong(), nextTtlNanos())) == null;
            if (opened) {
                log.warn("[CircuitBreaker] Obwód OTWARTY (DEGRADED) po {} kolejnych błędach: "
                                + "tenant={}, installation={}",
                        failures, tenantId, installationId);
                publish(PluginSyncEnvelope.Type.CIRCUIT_OPENED, tenantId, installationId);
            }
            persistDegraded(tenantId, installationId, failures);
        } else {
            log.debug("[CircuitBreaker] Błąd zarejestrowany: tenant={}, installation={}, "
                            + "kolejne błędy={}/{}",
                    tenantId, installationId, failures, FAILURE_THRESHOLD);
        }
    }
}
