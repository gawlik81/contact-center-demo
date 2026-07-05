package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.plugin.ExtensionPoint;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.pluginsdk.PluginContext;
import com.contactcenter.pluginsdk.model.ContactEvent;
import com.contactcenter.pluginsdk.model.CustomerSyncRequest;
import com.contactcenter.pluginsdk.model.DispositionEvent;
import com.contactcenter.pluginsdk.model.ManualActionRequest;
import com.contactcenter.pluginsdk.model.ManualActionResult;
import com.contactcenter.pluginsdk.model.PreContactConnectResult;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Implementacja {@link ExtensionPointPublisher} — dispatch + fault containment
 * (ARCHITECTURE.md §11.5/§11.7/§11.8, EPIC-28, BE-102/BE-104).
 *
 * <p><strong>Blocking vs. fire-and-forget (BE-104):</strong> {@code publishPreContactConnect}/
 * {@code publishManualAction} wywołują plugin bezpośrednio na {@code pluginInvocationExecutor},
 * blocking, timeout-bounded (logika niezmieniona od BE-102). {@code publishPostContactEnd}/
 * {@code publishCustomerSync}/{@code publishDispositionSet} NIE wywołują pluginu w tej klasie —
 * serializują payload do {@link PluginInvocationMessage} i publikują go na
 * {@code cc.queue.plugin-invocation} przez {@code RabbitTemplate}; lookup instalacji, wywołanie
 * pluginu, timeout, circuit breaker i logowanie dla tych trzech punktów rozszerzeń żyją w
 * {@link PluginInvocationConsumer} (BE-104, zastępuje tymczasowy submit-and-forget z BE-102).
 *
 * <p><strong>Decyzja o łączeniu wyników wielu instalacji ({@code publishPreContactConnect}):</strong>
 * gdy więcej niż jedna instalacja jest zarejestrowana na {@code PRE_CONTACT_CONNECT} dla tego
 * tenanta, ten serwis NIE mergeuje {@code displayData}/{@code warning} z wielu instalacji — SDK
 * ({@code PreContactConnectResult}) nie definiuje semantyki łączenia (np. jak rozstrzygnąć dwa
 * różne {@code warning} albo kolidujące klucze w {@code displayData}), a niejawny merge mapy
 * mógłby nadpisać dane jednego pluginu danymi innego bez ostrzeżenia. Zamiast tego: instalacje
 * są próbowane w porządku rejestracji ({@code display_order}, zachowanym przez
 * {@link PluginRegistry}) i zwracany jest wynik <strong>pierwszej instalacji, której wywołanie
 * zakończyło się sukcesem i zwróciło wynik niepusty</strong> (tj. {@code !displayData.isEmpty()}
 * lub {@code warning != null}) — jeśli żadna instalacja nie zwróci wyniku niepustego (lub lista
 * jest pusta), zwracany jest {@code PreContactConnectResult.empty()}. Każda próbowana instalacja
 * jest mimo to w pełni zarejestrowana w logu/circuit breakerze — "pierwszy niepusty wygrywa"
 * determinuje tylko co dostaje agent, nie które instalacje są uznane za wywołane.
 *
 * <p><strong>Granica wątku ({@code TenantContext} + TCCL):</strong> każde wywołanie kodu pluginu
 * jest opakowane przez {@code TenantContext.snapshot()} na wątku wywołującym →
 * {@code pluginInvocationExecutor.submit(...)} → {@code TenantContext.restore(snapshot)} w
 * {@code try} na wątku roboczym → {@link PluginExecutionContext#runWithPluginClassLoader} wokół
 * KAŻDEGO wywołania metody na {@code PluginEntryPoint} (nie tylko {@code onActivate}/
 * {@code onDeactivate} — warunek naprawy code review BE-101, reużyty tutaj) →
 * {@code TenantContext.clear()} w {@code finally} (CLAUDE.md, ARCHITECTURE.md §11.8).
 *
 * <p><strong>Zawieranie wyjątków:</strong> {@code catch (Throwable t)} na granicy wykonania
 * pluginu — nie tylko {@code Exception} — bo plugin może rzucić {@code Error}
 * (ARCHITECTURE.md §11.7 "Exception containment").
 */
@Slf4j
@Service
@RequiredArgsConstructor
class ExtensionPointPublisherImpl implements ExtensionPointPublisher {

    private final PluginRegistry pluginRegistry;
    private final CircuitBreakerState circuitBreakerState;
    private final CustomerService customerService;
    private final ContactService contactService;
    private final PluginInvocationProperties properties;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final PluginInvocationLogService pluginInvocationLogService;
    private final PluginCatalogQueryService pluginCatalogQueryService;

    @Qualifier("pluginInvocationExecutor")
    private final ExecutorService pluginInvocationExecutor;

    // =========================================================================
    // PRE_CONTACT_CONNECT — blocking
    // =========================================================================

    @Override
    public PreContactConnectResult publishPreContactConnect(UUID tenantId, ContactEvent event) {
        List<PluginInstanceHandle> handles = pluginRegistry.lookup(tenantId, ExtensionPoint.PRE_CONTACT_CONNECT);
        long timeoutMs = properties.effectivePreContactConnectTimeoutMs();

        for (PluginInstanceHandle handle : handles) {
            PreContactConnectResult result = invokeBlocking(
                    tenantId, handle, ExtensionPoint.PRE_CONTACT_CONNECT, timeoutMs,
                    ctx -> handle.entryPoint().onPreContactConnect(ctx, event),
                    PreContactConnectResult::empty,
                    event.contactId(), event);

            boolean nonEmpty = !result.displayData().isEmpty() || result.warning() != null;
            if (nonEmpty) {
                return result;
            }
        }

        return PreContactConnectResult.empty();
    }

    // =========================================================================
    // MANUAL_ACTION — blocking, instalacja konkretna
    // =========================================================================

    @Override
    public ManualActionResult publishManualAction(UUID tenantId, UUID installationId, ManualActionRequest req) {
        List<PluginInstanceHandle> handles = pluginRegistry.lookup(tenantId, ExtensionPoint.MANUAL_ACTION);
        PluginInstanceHandle handle = handles.stream()
                .filter(h -> h.installationId().equals(installationId))
                .findFirst()
                .orElse(null);

        if (handle == null) {
            log.warn("[ExtensionPointPublisher] publishManualAction: instalacja nieaktywna/nieznaleziona "
                            + "dla MANUAL_ACTION: tenant={}, installation={}",
                    tenantId, installationId);
            recordInvocation(tenantId, installationId, ExtensionPoint.MANUAL_ACTION,
                    InvocationStatus.SKIPPED_DISABLED, 0L,
                    "Instalacja nieaktywna lub nie zadeklarowała MANUAL_ACTION",
                    req.contactId(), req);
            return ManualActionResult.unsupported();
        }

        long timeoutMs = properties.effectiveManualActionTimeoutMs();
        return invokeBlocking(
                tenantId, handle, ExtensionPoint.MANUAL_ACTION, timeoutMs,
                ctx -> handle.entryPoint().onManualAction(ctx, req),
                ManualActionResult::unsupported,
                req.contactId(), req);
    }

    // =========================================================================
    // Fire-and-forget — POST_CONTACT_END / CUSTOMER_SYNC / DISPOSITION_SET
    //
    // BE-104: publikacja do RabbitMQ (cc.queue.plugin-invocation), konsumowana asynchronicznie
    // przez PluginInvocationConsumer — lookup instalacji, wywołanie pluginu, timeout, circuit
    // breaker i logowanie żyją teraz w konsumencie, NIE tutaj (poprzednia implementacja BE-102,
    // submit-and-forget na pluginInvocationExecutor bez RabbitMQ, była tymczasowym placeholderem
    // zastąpionym w tym tickecie).
    // =========================================================================

    @Override
    public void publishPostContactEnd(UUID tenantId, ContactEvent event) {
        publishToQueue(tenantId, ExtensionPoint.POST_CONTACT_END, event);
    }

    @Override
    public void publishCustomerSync(UUID tenantId, CustomerSyncRequest req) {
        publishToQueue(tenantId, ExtensionPoint.CUSTOMER_SYNC, req);
    }

    @Override
    public void publishDispositionSet(UUID tenantId, DispositionEvent event) {
        publishToQueue(tenantId, ExtensionPoint.DISPOSITION_SET, event);
    }

    // =========================================================================
    // Mechanizm wspólny — blocking
    // =========================================================================

    /**
     * Wywołuje {@code invocation} na jednej instalacji, blocking, timeout-bounded.
     *
     * <p>Sekwencja zgodna z ARCHITECTURE.md §11.5/§11.7/§11.8:
     * <ol>
     *   <li>circuit breaker {@code OPEN}? → {@code CIRCUIT_OPEN}, zwróć domyślny wynik, NIE
     *       wywołuj pluginu</li>
     *   <li>{@code TenantContext.snapshot()} na wątku wywołującym</li>
     *   <li>{@code pluginInvocationExecutor.submit(...)} → na wątku roboczym:
     *       {@code TenantContext.restore(snapshot)} w {@code try},
     *       {@link PluginExecutionContext#runWithPluginClassLoader} wokół wywołania pluginu,
     *       {@code catch (Throwable)}, {@code finally TenantContext.clear()}</li>
     *   <li>{@code future.get(timeoutMs, MILLISECONDS)} na wątku WYWOŁUJĄCYM — na
     *       {@code TimeoutException}: {@code future.cancel(true)} (best-effort interrupt),
     *       {@code TIMED_OUT}, zwróć domyślny wynik</li>
     *   <li>każda ścieżka aktualizuje circuit breaker i zapisuje invocation</li>
     * </ol>
     */
    private <R> R invokeBlocking(
            UUID tenantId,
            PluginInstanceHandle handle,
            ExtensionPoint extensionPoint,
            long timeoutMs,
            Function<PluginContext, R> invocation,
            Supplier<R> defaultResult,
            UUID relatedContactId,
            Object requestPayload) {

        if (circuitBreakerState.isOpen(handle.installationId())) {
            recordInvocation(tenantId, handle.installationId(), extensionPoint,
                    InvocationStatus.CIRCUIT_OPEN, 0L,
                    "Circuit breaker otwarty (>= " + CircuitBreakerState.FAILURE_THRESHOLD
                            + " kolejnych błędów)",
                    relatedContactId, requestPayload);
            return defaultResult.get();
        }

        TenantContext.Snapshot snapshot = TenantContext.snapshot();
        long startedAtNanos = System.nanoTime();

        Future<R> future = pluginInvocationExecutor.submit(() -> runOnWorkerThread(
                snapshot, tenantId, handle, invocation));

        try {
            R result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            long durationMs = elapsedMillis(startedAtNanos);
            circuitBreakerState.recordResult(tenantId, handle.installationId(), true);
            recordInvocation(tenantId, handle.installationId(), extensionPoint,
                    InvocationStatus.SUCCESS, durationMs, null, relatedContactId, requestPayload);
            return result;
        } catch (TimeoutException e) {
            // "Timeout = no kill" (ARCHITECTURE.md §11.7) — JVM nie może wymusić zatrzymania
            // wątku roboczego; best-effort interrupt, wątek może ignorować i dokończyć się jako
            // orphaned "fire and forget" sink. Wołający NIE czeka dłużej.
            future.cancel(true);
            long durationMs = elapsedMillis(startedAtNanos);
            circuitBreakerState.recordResult(tenantId, handle.installationId(), false);
            recordInvocation(tenantId, handle.installationId(), extensionPoint,
                    InvocationStatus.TIMED_OUT, durationMs,
                    "Przekroczono timeout " + timeoutMs + "ms",
                    relatedContactId, requestPayload);
            return defaultResult.get();
        } catch (ExecutionException e) {
            long durationMs = elapsedMillis(startedAtNanos);
            circuitBreakerState.recordResult(tenantId, handle.installationId(), false);
            String errorSummary = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            recordInvocation(tenantId, handle.installationId(), extensionPoint,
                    InvocationStatus.FAILED, durationMs, errorSummary, relatedContactId, requestPayload);
            return defaultResult.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long durationMs = elapsedMillis(startedAtNanos);
            circuitBreakerState.recordResult(tenantId, handle.installationId(), false);
            recordInvocation(tenantId, handle.installationId(), extensionPoint,
                    InvocationStatus.FAILED, durationMs, "Wątek wywołujący przerwany",
                    relatedContactId, requestPayload);
            return defaultResult.get();
        }
    }

    /**
     * Kod wykonywany na wątku roboczym {@code pluginInvocationExecutor} — przywraca
     * {@code TenantContext}, ustawia granicę TCCL przez
     * {@link PluginExecutionContext#runWithPluginClassLoader}, woła plugin, zawiera
     * {@code Throwable} (KRYTYCZNE: nie tylko {@code Exception} — plugin może rzucić
     * {@code Error}), czyści {@code TenantContext} w {@code finally}.
     */
    private <R> R runOnWorkerThread(
            TenantContext.Snapshot snapshot,
            UUID tenantId,
            PluginInstanceHandle handle,
            Function<PluginContext, R> invocation) {
        TenantContext.restore(snapshot);
        try {
            PluginContext pluginContext = buildPluginContext(tenantId, handle);
            Callable<R> action = () -> invocation.apply(pluginContext);
            return PluginExecutionContext.runWithPluginClassLoader(handle.classLoader(), action);
        } catch (Throwable t) {
            // Granica obowiązkowa (ARCHITECTURE.md §11.7 "Exception containment") — propagacja
            // przez ExecutionException do wątku wywołującego, NIGDY dalej niewrapped.
            throw new PluginInvocationFailedException(
                    "Wywołanie pluginu " + handle.pluginKey() + " nie powiodło się: " + t.getMessage(), t);
        } finally {
            TenantContext.clear();
        }
    }

    // =========================================================================
    // Mechanizm wspólny — fire-and-forget (publikacja do RabbitMQ, BE-104)
    // =========================================================================

    /**
     * Serializuje {@code eventPayload} (jeden z rekordów SDK: {@code ContactEvent}/
     * {@code CustomerSyncRequest}/{@code DispositionEvent}) do {@link PluginInvocationMessage}
     * i publikuje go na {@code cc.queue.plugin-invocation} (exchange {@code cc.events},
     * routing key {@code plugin.invocation}) — nie wywołuje żadnego pluginu bezpośrednio i nie
     * sprawdza {@link PluginRegistry#lookup} (to jest teraz odpowiedzialność
     * {@code PluginInvocationConsumer} w momencie konsumpcji, zob. Javadoc
     * {@link PluginInvocationMessage}).
     *
     * <p>Wołający (request thread lub RabbitMQ listener thread innego konsumenta, np.
     * {@code CallEventEnricher}) nie czeka na żadne potwierdzenie wywołania pluginu —
     * {@code RabbitTemplate#convertAndSend} jest non-blocking względem konsumenta (publisher
     * confirm jest asynchroniczny, skonfigurowany w {@code RabbitMQConfig#rabbitTemplate}).
     * Błąd samej publikacji (broker niedostępny) jest logowany i NIE propagowany do wołającego —
     * zgodnie z klasyfikacją fire-and-forget tego punktu rozszerzenia (ARCHITECTURE.md §11.5),
     * utrata jednej próby publikacji nie może zepsuć przepływu domenowego (np. zakończenia
     * kontaktu), który wywołał ten publish.
     */
    private void publishToQueue(UUID tenantId, ExtensionPoint extensionPoint, Object eventPayload) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payloadAsMap =
                    objectMapper.convertValue(eventPayload, Map.class);
            PluginInvocationMessage message = new PluginInvocationMessage(
                    tenantId, extensionPoint.name(), payloadAsMap, Instant.now());

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_EVENTS, RabbitMQConfig.RK_PLUGIN_INVOCATION, message);

            log.debug("[ExtensionPointPublisher] Opublikowano do {} (rk={}): tenant={}, extensionPoint={}",
                    RabbitMQConfig.EXCHANGE_EVENTS, RabbitMQConfig.RK_PLUGIN_INVOCATION,
                    tenantId, extensionPoint);
        } catch (Exception e) {
            // Publikacja jest non-critical względem przepływu wołającego — np. zakończenie
            // kontaktu (POST_CONTACT_END) musi się powiedzieć niezależnie od dostępności
            // RabbitMQ. Brak instalacji/dostarczenia jest w tym wypadku akceptowalną utratą
            // jednego wywołania pluginu, nie błędem do propagacji.
            log.error("[ExtensionPointPublisher] Błąd publikacji do {} dla extensionPoint={}, tenant={}: {}",
                    RabbitMQConfig.QUEUE_PLUGIN_INVOCATION, extensionPoint, tenantId, e.getMessage(), e);
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private PluginContext buildPluginContext(UUID tenantId, PluginInstanceHandle handle) {
        // PluginContextImpl skonstruowany per wywołanie (nie cache'owany) z tenantId pochodzącym
        // z TenantContext wątku wywołującego — nigdy od pluginu (kontrakt zgodny z
        // PluginRuntimeManagerImpl.load, patrz Javadoc PluginContextImpl).
        //
        // grantedPermissions pochodzi z handle (BE-101, niezmienne dla czasu życia instalacji —
        // zmiana uprawnień wymaga reinstalacji, która tworzy nowy handle). installationConfig
        // jest odczytywany ŚWIEŻO z bazy przy KAŻDYM wywołaniu (analogicznie do filozofii
        // TenantContext w tym systemie — nigdy statycznie cache'owany), bo admin może zmienić
        // konfigurację instalacji (PATCH /api/supervisor/plugins/installations/{id}/config,
        // BE-108) bez disable/enable, co by inaczej nigdy nie weszło w życie. Poprawka bugu
        // krytycznego: poprzednio ZAWSZE List.of()/null tutaj, niezależnie od rzeczywistych
        // danych instalacji — zob. TASKS-BACKEND.md, BE-101/BE-102.
        String installationConfig = pluginCatalogQueryService.findInstallation(tenantId, handle.installationId())
                .map(TenantPluginInstallation::getInstallationConfig)
                .orElse(null);

        return new PluginContextImpl(
                tenantId,
                handle.pluginKey(),
                handle.grantedPermissions(),
                installationConfig,
                customerService,
                contactService);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /**
     * Zapisuje wynik jednej próby wywołania do {@code plugin_invocation_log} (BE-105) —
     * {@code requestPayload} jest redagowany ({@code PiiRedactor}) wewnątrz
     * {@link PluginInvocationLogService} przed zapisem, nigdy surowy PII klienta.
     *
     * <p>Zastępuje placeholder SLF4J ({@code PluginInvocationLogger}, BE-102/104, usunięty w
     * BE-105) — wołane identycznie z {@link PluginInvocationConsumer}, oba miejsca wstrzykują
     * teraz {@link PluginInvocationLogService} bezpośrednio.
     */
    private void recordInvocation(
            UUID tenantId, UUID installationId, ExtensionPoint extensionPoint,
            InvocationStatus status, long durationMs, String errorSummary,
            UUID relatedContactId, Object requestPayload) {
        pluginInvocationLogService.record(tenantId, installationId, extensionPoint, status,
                durationMs, errorSummary, relatedContactId, requestPayload);
    }
}
