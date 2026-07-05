package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.contact.ContactService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.messaging.TenantAwareConsumer;
import com.contactcenter.domain.plugin.ExtensionPoint;
import com.contactcenter.domain.plugin.PluginCatalogQueryService;
import com.contactcenter.domain.plugin.PluginRegistrationService;
import com.contactcenter.domain.plugin.TenantPluginInstallation;
import com.contactcenter.domain.plugin.dto.TenantPluginInstallationDto;
import com.contactcenter.infrastructure.config.RabbitMQConfig;
import com.contactcenter.pluginsdk.PluginContext;
import com.contactcenter.pluginsdk.model.ContactEvent;
import com.contactcenter.pluginsdk.model.CustomerSyncRequest;
import com.contactcenter.pluginsdk.model.DispositionEvent;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Konsument {@code cc.queue.plugin-invocation} — wywołuje plugin dla punktów rozszerzeń
 * fire-and-forget ({@code POST_CONTACT_END}/{@code CUSTOMER_SYNC}/{@code DISPOSITION_SET},
 * ARCHITECTURE.md §11.5/§11.11, EPIC-28, BE-104).
 *
 * <p><strong>Sekwencja przetwarzania jednej wiadomości:</strong>
 * <ol>
 *   <li>{@link TenantAwareConsumer#processWithTenant} ustawia {@code TenantContext} na
 *       {@code message.tenantId()} dla CAŁEGO przetwarzania tej wiadomości (lookup, wywołanie
 *       pluginu, aktualizacja circuit breakera) — wątek listenera RabbitMQ nie ma
 *       {@code TenantContext} ustawionego przez żaden filtr HTTP</li>
 *   <li>{@link PluginRegistry#lookup} wszystkich instalacji zarejestrowanych na
 *       {@code message.extensionPoint()} dla tego tenanta — NIE jedna konkretna instalacja
 *       z chwili publikacji, zob. Javadoc {@link PluginInvocationMessage}</li>
 *   <li>dla każdej znalezionej instalacji: pobranie aktualnego stanu {@code enabled} z
 *       {@link PluginRegistrationService#getInstallation} — jeśli {@code false}, log
 *       {@code SKIPPED_DISABLED} (NIE silent drop, kryterium akceptacji tego ticketu) i
 *       przejście do następnej instalacji bez wywołania pluginu</li>
 *   <li>circuit breaker {@code OPEN}? → {@code CIRCUIT_OPEN}, bez wywołania pluginu (stan
 *       WSPÓLNY z {@link ExtensionPointPublisherImpl} — ten sam bean
 *       {@link CircuitBreakerState}, bo circuit breaker jest per {@code installationId}
 *       niezależnie od tego, czy wywołanie jest blocking czy async)</li>
 *   <li>wywołanie metody {@code PluginEntryPoint} odpowiadającej
 *       {@code message.extensionPoint()}, timeout-bounded
 *       ({@link PluginInvocationProperties#effectiveAsyncInvocationTimeoutMs()}, domyślnie
 *       30000ms), na {@code pluginInvocationExecutor} (ten sam executor co BE-102 — odrębny od
 *       wątku listenera RabbitMQ, więc plugin zawieszony nie blokuje konsumpcji KOLEJNYCH
 *       wiadomości z tej kolejki na wątku listenera; granica {@code TenantContext}/TCCL wokół
 *       wywołania pluginu jak w {@link ExtensionPointPublisherImpl})</li>
 *   <li>każda ścieżka (SUCCESS/FAILED/TIMED_OUT/CIRCUIT_OPEN/SKIPPED_DISABLED) →
 *       {@link PluginInvocationLogService#record} + aktualizacja {@link CircuitBreakerState}</li>
 * </ol>
 *
 * <p><strong>Retry i DLQ:</strong> ta klasa NIE łapie wyjątków na granicy
 * {@link #handleInvocation} — jeśli {@link PluginRegistry#lookup} lub
 * {@link PluginRegistrationService#getInstallation} rzuci (np. błąd JDBC), wyjątek propaguje się
 * do Spring AMQP, które nack-uje wiadomość; globalny retry (
 * {@code spring.rabbitmq.listener.simple.retry}, skonfigurowany w
 * {@code application-dev.yml}/{@code application-prod.yml}, max-attempts 3/5) ponawia
 * dostarczenie, a po wyczerpaniu prób wiadomość trafia do {@code cc.dlx} →
 * {@code cc.queue.dead-letter} → {@code DeadLetterConsumer} (wzorzec identyczny z
 * {@code AuditLogConsumer}). Wyjątek/{@code Error} rzucony PRZEZ KOD PLUGINU jest natomiast
 * zawsze złapany WEWNĄTRZ {@link #invokeWithTimeout} (jak w {@link ExtensionPointPublisherImpl})
 * — plugin, który rzuca, nigdy nie powoduje nack/retry/DLQ tej wiadomości, bo z punktu widzenia
 * kolejki to jest poprawnie przetworzona wiadomość, której skutek biznesowy ("plugin się nie
 * powiódł") jest tylko zalogowany jako {@code FAILED}, nie błędem infrastrukturalnym.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class PluginInvocationConsumer extends TenantAwareConsumer {

    private final PluginRegistry pluginRegistry;
    private final PluginRegistrationService pluginRegistrationService;
    private final CircuitBreakerState circuitBreakerState;
    private final CustomerService customerService;
    private final ContactService contactService;
    private final PluginInvocationProperties properties;
    private final ObjectMapper objectMapper;
    private final PluginInvocationLogService pluginInvocationLogService;
    private final PluginCatalogQueryService pluginCatalogQueryService;

    @Qualifier("pluginInvocationExecutor")
    private final ExecutorService pluginInvocationExecutor;

    @RabbitListener(queues = RabbitMQConfig.QUEUE_PLUGIN_INVOCATION)
    public void handleInvocation(PluginInvocationMessage message) {
        if (message == null) {
            log.warn("[PluginInvocationConsumer] Otrzymano null wiadomość – ignoruję");
            return;
        }
        if (message.tenantId() == null) {
            log.warn("[PluginInvocationConsumer] Wiadomość bez tenantId – ignoruję: extensionPoint={}",
                    message.extensionPoint());
            return;
        }

        processWithTenant(message.tenantId(), () -> doHandle(message));
    }

    private void doHandle(PluginInvocationMessage message) {
        ExtensionPoint extensionPoint = parseExtensionPoint(message.extensionPoint());
        if (extensionPoint == null) {
            log.error("[PluginInvocationConsumer] extensionPoint nierozpoznany: '{}' – wiadomość odrzucona "
                            + "(brak retry, błąd kontraktu, nie infrastruktury)",
                    message.extensionPoint());
            return;
        }

        UUID tenantId = message.tenantId();
        List<PluginInstanceHandle> handles = pluginRegistry.lookup(tenantId, extensionPoint);
        if (handles.isEmpty()) {
            log.debug("[PluginInvocationConsumer] Brak instalacji zarejestrowanych dla tenant={}, "
                            + "extensionPoint={} – nic do zrobienia",
                    tenantId, extensionPoint);
            return;
        }

        for (PluginInstanceHandle handle : handles) {
            processOneInstallation(tenantId, handle, extensionPoint, message);
        }
    }

    private void processOneInstallation(
            UUID tenantId, PluginInstanceHandle handle, ExtensionPoint extensionPoint,
            PluginInvocationMessage message) {

        UUID installationId = handle.installationId();

        // Payload zdeserializowany na samym początku — potrzebny do EKSTRAKCJI
        // relatedContactId i jako requestPayload dla KAŻDEJ ścieżki record(...) poniżej
        // (SKIPPED_DISABLED/CIRCUIT_OPEN włącznie, nie tylko po faktycznym wywołaniu pluginu).
        Object eventPayload = deserializePayload(extensionPoint, message);
        UUID relatedContactId = extractContactId(extensionPoint, eventPayload);

        // Krok 1: stan enabled w DB jest źródłem prawdy aktualnym w chwili KONSUMPCJI, nie
        // publikacji — instalacja mogła zostać disabled między tymi dwoma momentami (kryterium
        // akceptacji tego ticketu). PluginRegistry.lookup nie wie nic o tej fladze (rejestr w
        // pamięci jest zarządzany jawnym register()/unregister(), nie odzwierciedla automatycznie
        // enable()/disable() w DB – zob. komentarz w PluginRegistryImpl).
        TenantPluginInstallationDto installation;
        try {
            installation = pluginRegistrationService.getInstallation(tenantId, installationId);
        } catch (RuntimeException e) {
            // Instalacja usunięta całkowicie między publikacją a konsumpcją – traktujemy jak
            // disabled (nic do wywołania), nie jak błąd infrastrukturalny do retry.
            log.warn("[PluginInvocationConsumer] Instalacja nieznaleziona w DB: tenant={}, installation={}, "
                            + "extensionPoint={} – traktuję jak SKIPPED_DISABLED",
                    tenantId, installationId, extensionPoint);
            pluginInvocationLogService.record(tenantId, installationId, extensionPoint,
                    InvocationStatus.SKIPPED_DISABLED, 0L, "Instalacja nie istnieje w DB: " + e.getMessage(),
                    relatedContactId, eventPayload);
            return;
        }

        if (!installation.enabled()) {
            log.info("[PluginInvocationConsumer] Instalacja disabled: tenant={}, installation={}, "
                            + "extensionPoint={} – SKIPPED_DISABLED (nie silent drop)",
                    tenantId, installationId, extensionPoint);
            pluginInvocationLogService.record(tenantId, installationId, extensionPoint,
                    InvocationStatus.SKIPPED_DISABLED, 0L, "Instalacja wyłączona (enabled=false)",
                    relatedContactId, eventPayload);
            return;
        }

        // Krok 2: circuit breaker – wspólny stan w pamięci z ExtensionPointPublisherImpl, bo
        // jest indeksowany tylko po installationId, nie po ścieżce wywołania (blocking/async).
        if (circuitBreakerState.isOpen(installationId)) {
            pluginInvocationLogService.record(tenantId, installationId, extensionPoint,
                    InvocationStatus.CIRCUIT_OPEN, 0L,
                    "Circuit breaker otwarty (>= " + CircuitBreakerState.FAILURE_THRESHOLD
                            + " kolejnych błędów)",
                    relatedContactId, eventPayload);
            return;
        }

        // Krok 3: wywołanie pluginu, timeout-bounded, na pluginInvocationExecutor.
        long timeoutMs = properties.effectiveAsyncInvocationTimeoutMs();
        long startedAtNanos = System.nanoTime();

        try {
            invokeWithTimeout(tenantId, handle, extensionPoint, eventPayload, timeoutMs);
            long durationMs = elapsedMillis(startedAtNanos);
            circuitBreakerState.recordResult(tenantId, installationId, true);
            pluginInvocationLogService.record(tenantId, installationId, extensionPoint,
                    InvocationStatus.SUCCESS, durationMs, null, relatedContactId, eventPayload);
        } catch (TimeoutException e) {
            long durationMs = elapsedMillis(startedAtNanos);
            circuitBreakerState.recordResult(tenantId, installationId, false);
            pluginInvocationLogService.record(tenantId, installationId, extensionPoint,
                    InvocationStatus.TIMED_OUT, durationMs, "Przekroczono timeout " + timeoutMs + "ms",
                    relatedContactId, eventPayload);
        } catch (ExecutionException e) {
            long durationMs = elapsedMillis(startedAtNanos);
            circuitBreakerState.recordResult(tenantId, installationId, false);
            String errorSummary = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            pluginInvocationLogService.record(tenantId, installationId, extensionPoint,
                    InvocationStatus.FAILED, durationMs, errorSummary, relatedContactId, eventPayload);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long durationMs = elapsedMillis(startedAtNanos);
            circuitBreakerState.recordResult(tenantId, installationId, false);
            pluginInvocationLogService.record(tenantId, installationId, extensionPoint,
                    InvocationStatus.FAILED, durationMs, "Wątek listenera przerwany",
                    relatedContactId, eventPayload);
        }
    }

    /**
     * Wyciąga {@code contactId} z payloadu, jeśli dostępny — {@code ContactEvent}/
     * {@code DispositionEvent} mają to pole, {@code CustomerSyncRequest} nie (brak kontekstu
     * kontaktu dla synchronizacji klienta).
     */
    private static UUID extractContactId(ExtensionPoint extensionPoint, Object eventPayload) {
        return switch (extensionPoint) {
            case POST_CONTACT_END -> ((ContactEvent) eventPayload).contactId();
            case DISPOSITION_SET -> ((DispositionEvent) eventPayload).contactId();
            default -> null;
        };
    }

    /**
     * Wywołuje metodę {@code PluginEntryPoint} odpowiadającą {@code extensionPoint} na
     * {@code pluginInvocationExecutor}, blocking na WĄTKU LISTENERA do {@code timeoutMs} (wątek
     * listenera RabbitMQ jest tu "wołającym", analogicznie do request thread w
     * {@link ExtensionPointPublisherImpl#invokeBlocking}) — granica {@code TenantContext}/TCCL
     * identyczna jak w BE-102: snapshot na wątku wołającym, restore w {@code try} na wątku
     * roboczym, {@link PluginExecutionContext#runWithPluginClassLoader} wokół wywołania, clear w
     * {@code finally}, {@code catch (Throwable)} (plugin może rzucić {@code Error}).
     */
    private void invokeWithTimeout(
            UUID tenantId, PluginInstanceHandle handle, ExtensionPoint extensionPoint,
            Object eventPayload, long timeoutMs)
            throws TimeoutException, ExecutionException, InterruptedException {

        TenantContext.Snapshot snapshot = TenantContext.snapshot();

        Future<Void> future = pluginInvocationExecutor.submit(() -> {
            TenantContext.restore(snapshot);
            try {
                PluginContext pluginContext = buildPluginContext(tenantId, handle);
                Callable<Void> action = () -> {
                    invokePluginMethod(handle, extensionPoint, pluginContext, eventPayload);
                    return null;
                };
                return PluginExecutionContext.runWithPluginClassLoader(handle.classLoader(), action);
            } catch (Throwable t) {
                throw new PluginInvocationFailedException(
                        "Wywołanie pluginu " + handle.pluginKey() + " nie powiodło się: " + t.getMessage(), t);
            } finally {
                TenantContext.clear();
            }
        });

        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // "Timeout = no kill" (ARCHITECTURE.md §11.7) – best-effort interrupt, wątek roboczy
            // może ignorować i dokończyć się jako orphaned sink; konsument NIE czeka dłużej.
            future.cancel(true);
            throw e;
        }
    }

    private void invokePluginMethod(
            PluginInstanceHandle handle, ExtensionPoint extensionPoint,
            PluginContext pluginContext, Object eventPayload) {
        switch (extensionPoint) {
            case POST_CONTACT_END ->
                    handle.entryPoint().onPostContactEnd(pluginContext, (ContactEvent) eventPayload);
            case CUSTOMER_SYNC ->
                    handle.entryPoint().onCustomerSync(pluginContext, (CustomerSyncRequest) eventPayload);
            case DISPOSITION_SET ->
                    handle.entryPoint().onDispositionSet(pluginContext, (DispositionEvent) eventPayload);
            default -> throw new IllegalStateException(
                    "PluginInvocationConsumer nie obsługuje extensionPoint=" + extensionPoint
                            + " (oczekiwano POST_CONTACT_END/CUSTOMER_SYNC/DISPOSITION_SET)");
        }
    }

    /**
     * Deserializuje {@code message.eventPayload()} (zdekodowany przez Jackson jako
     * {@code Map<String,Object>}, zob. Javadoc {@link PluginInvocationMessage}) z powrotem na
     * rekord SDK odpowiadający {@code extensionPoint}.
     */
    private Object deserializePayload(ExtensionPoint extensionPoint, PluginInvocationMessage message) {
        return switch (extensionPoint) {
            case POST_CONTACT_END -> objectMapper.convertValue(message.eventPayload(), ContactEvent.class);
            case CUSTOMER_SYNC -> objectMapper.convertValue(message.eventPayload(), CustomerSyncRequest.class);
            case DISPOSITION_SET -> objectMapper.convertValue(message.eventPayload(), DispositionEvent.class);
            default -> throw new IllegalStateException(
                    "PluginInvocationConsumer nie obsługuje extensionPoint=" + extensionPoint);
        };
    }

    private PluginContext buildPluginContext(UUID tenantId, PluginInstanceHandle handle) {
        // Patrz komentarz w ExtensionPointPublisherImpl.buildPluginContext — identyczna naprawa
        // bugu krytycznego (TASKS-BACKEND.md, BE-101/BE-102): grantedPermissions z handle
        // (niezmienne dla czasu życia instalacji), installationConfig odczytany świeżo z bazy
        // przy KAŻDYM wywołaniu (admin może zmienić config bez disable/enable, BE-108).
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

    private static ExtensionPoint parseExtensionPoint(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ExtensionPoint.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long elapsedMillis(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
