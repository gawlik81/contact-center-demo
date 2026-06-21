package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.pluginsdk.model.ContactEvent;
import com.contactcenter.pluginsdk.model.CustomerSyncRequest;
import com.contactcenter.pluginsdk.model.DispositionEvent;
import com.contactcenter.pluginsdk.model.ManualActionRequest;
import com.contactcenter.pluginsdk.model.ManualActionResult;
import com.contactcenter.pluginsdk.model.PreContactConnectResult;

import java.util.UUID;

/**
 * Jedyny punkt dispatchu wywołań {@code PluginEntryPoint} (ARCHITECTURE.md §11.5/§11.7,
 * EPIC-28, BE-102) — żaden inny serwis backendu woła metody {@code PluginEntryPoint}
 * bezpośrednio; wszystkie ścieżki przechodzą przez {@link PluginRegistry#lookup} +
 * {@code PluginInvocationExecutor} + fault containment zaimplementowany tutaj.
 *
 * <p><strong>Klasyfikacja blocking vs. fire-and-forget (§11.5):</strong>
 * <ul>
 *   <li>{@link #publishPreContactConnect}/{@link #publishManualAction} — blokujące,
 *       timeout-bounded; wołający NIGDY nie czeka dłużej niż skonfigurowany timeout i NIGDY
 *       nie otrzymuje wyjątku — błąd/timeout/circuit-open zwracają zawsze wynik domyślny SDK
 *       ({@code *.empty()}/{@code *.unsupported()}).</li>
 *   <li>{@link #publishPostContactEnd}/{@link #publishCustomerSync}/{@link #publishDispositionSet}
 *       — fire-and-forget; od BE-104 publikowane jako {@code PluginInvocationMessage} na
 *       RabbitMQ ({@code cc.queue.plugin-invocation}) i konsumowane asynchronicznie przez
 *       {@code PluginInvocationConsumer}, który wykonuje lookup instalacji, wywołanie pluginu,
 *       timeout i circuit breaker — ta klasa NIE wywołuje pluginu dla tych trzech punktów.</li>
 * </ul>
 *
 * <p>Każda ścieżka (sukces, błąd, timeout, circuit-open, disabled) jest zapisywana — tymczasowo
 * tylko przez SLF4J ({@code PluginInvocationLogger}, wspólny dla
 * {@link ExtensionPointPublisherImpl} i {@code PluginInvocationConsumer}); BE-105 podmieni to
 * wołanie na {@code PluginInvocationLogService} w jednym miejscu, bez zmiany reszty tych klas.
 */
public interface ExtensionPointPublisher {

    /**
     * Wywołuje {@code onPreContactConnect} dla wszystkich aktywnych instalacji tenanta
     * zarejestrowanych na {@code PRE_CONTACT_CONNECT}.
     *
     * <p>Blocking, timeout domyślny {@code plugin.invocation.pre-contact-connect-timeout-ms}
     * (2000ms) — na timeout/błąd/circuit-open zwraca {@code PreContactConnectResult.empty()};
     * agent desktop nigdy nie czeka dłużej i nigdy nie blokuje core telephony na pluginie.
     *
     * @param tenantId tenant wywołujący (z {@code TenantContext} wątku wołającego)
     * @param event     zdarzenie kontaktu, które ma się połączyć
     * @return wynik pierwszej instalacji, która zwróciła niepusty/sukces wynik — patrz Javadoc
     *         {@link ExtensionPointPublisherImpl#publishPreContactConnect} dla pełnej decyzji
     *         o łączeniu wyników wielu instalacji
     */
    PreContactConnectResult publishPreContactConnect(UUID tenantId, ContactEvent event);

    /**
     * Wywołuje {@code onManualAction} na jednej, konkretnej instalacji wskazanej przez agenta
     * (kliknięcie przycisku w UI desktopu).
     *
     * <p>Blocking, timeout domyślny {@code plugin.invocation.manual-action-timeout-ms} (5000ms)
     * — na timeout/błąd/circuit-open zwraca {@code ManualActionResult.unsupported()}.
     *
     * @param tenantId       tenant wywołujący
     * @param installationId instalacja, której akcja ma zostać wywołana
     * @param req             żądanie akcji (identyfikator akcji + parametry agenta)
     * @return wynik wywołania, lub wynik domyślny SDK gdy instalacja nieaktywna/nieznaleziona/
     *         circuit-open/timeout/błąd
     */
    ManualActionResult publishManualAction(UUID tenantId, UUID installationId, ManualActionRequest req);

    /**
     * Fire-and-forget dispatch {@code onPostContactEnd} dla wszystkich instalacji tenanta
     * zarejestrowanych na {@code POST_CONTACT_END}.
     *
     * <p>Publikuje {@code event} na {@code cc.queue.plugin-invocation} (BE-104) i wraca
     * natychmiast — lookup instalacji i wywołanie pluginu odbywa się asynchronicznie w
     * {@code PluginInvocationConsumer}.
     */
    void publishPostContactEnd(UUID tenantId, ContactEvent event);

    /**
     * Fire-and-forget dispatch {@code onCustomerSync} dla wszystkich instalacji tenanta
     * zarejestrowanych na {@code CUSTOMER_SYNC}.
     */
    void publishCustomerSync(UUID tenantId, CustomerSyncRequest req);

    /**
     * Fire-and-forget dispatch {@code onDispositionSet} dla wszystkich instalacji tenanta
     * zarejestrowanych na {@code DISPOSITION_SET}.
     */
    void publishDispositionSet(UUID tenantId, DispositionEvent event);
}
