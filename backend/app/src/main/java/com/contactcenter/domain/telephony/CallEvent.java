package com.contactcenter.domain.telephony;

import com.contactcenter.domain.customer.CustomerCliResult;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Domenowy event telefoniczny publikowany na RabbitMQ (exchange {@code cc.events}).
 *
 * <p>Routing key: {@code call.{eventType.toLowerCase()}} – np. {@code call.incoming},
 * {@code call.answered}, {@code call.hangup}, {@code call.transferred}.
 *
 * <p>Mapuje zarówno zdarzenia z webhooków zewnętrznych providerów (Twilio, Vonage)
 * jak i zdarzenia generowane przez {@link MockTelephonyAdapter}.
 */
@Getter
@Builder
public class CallEvent {

    /** Typ zdarzenia – determinuje routing key w RabbitMQ. */
    private final EventType eventType;

    /** Identyfikator sesji połączenia (z systemu providera lub wygenerowany lokalnie). */
    private final String callId;

    /**
     * UUID rekordu kontaktu w tabeli {@code contact}.
     *
     * <p>Wypełniany przez {@link MockTelephonyAdapter} (i docelowo przez webhook handler
     * rzeczywistego providera), który tworzy rekord w DB przed opublikowaniem eventu.
     * Gdy ustawiony, frontend używa tego UUID do operacji REST (np. {@code PATCH /api/contacts/{contactId}/disposition})
     * zamiast surowego {@code callId}. {@code null} oznacza brak persystowanego rekordu (tryb legacy).
     */
    private final UUID contactId;

    /** Tenant, do którego należy zdarzenie. */
    private final UUID tenantId;

    /** Agent powiązany ze zdarzeniem (może być null dla zdarzeń przychodzących). */
    private final UUID agentId;

    /** Numer dzwoniącego (format E.164). */
    private final String from;

    /** Numer docelowy (format E.164). */
    private final String to;

    /** Czas wystąpienia zdarzenia. */
    private final Instant timestamp;

    /**
     * Wynik połączenia zwrócony przez Twilio (lub dostawcę telefonii).
     *
     * <p>Możliwe wartości: {@code "completed"} (rozmowa odbyła się), {@code "no-answer"}
     * (brak odpowiedzi), {@code "busy"} (zajęty), {@code "failed"} (błąd sieci/providera),
     * {@code "canceled"} (połączenie anulowane przed odebraniem).
     * Wartość {@code null} dla eventów innych niż CALL_HANGUP.
     */
    private final String callOutcome;

    /**
     * Dodatkowe metadane specyficzne dla typu zdarzenia lub providera.
     * Przykład: {"transferTarget": "+48987654321", "transferType": "BLIND"}
     */
    private final Map<String, String> metadata;

    /**
     * Dane klienta zidentyfikowanego przez CLI lookup (BE-011).
     *
     * <p>Wypełniane przez {@code CallEventEnricher} dla zdarzeń {@code CALL_INCOMING}
     * przed przekazaniem eventu do WebSocket. Wartość {@code null} oznacza nieznany numer.
     */
    private final CustomerCliResult customerInfo;

    /**
     * Dane wzbogacenia z pluginu {@code PRE_CONTACT_CONNECT} (EPIC-28, BE-103),
     * {@code PreContactConnectResult.displayData()} — para klucz/wartość do wyświetlenia
     * agentowi w panelu klienta (np. "ostatnie 3 zamówienia z CRM").
     *
     * <p>Wypełniane przez {@code CallEventEnricher} po wywołaniu
     * {@code ExtensionPointPublisher.publishPreContactConnect}. {@code null} (nie pusta mapa)
     * gdy tenant nie ma zainstalowanych pluginów na tym extension point, plugin zwrócił wynik
     * pusty, lub wystąpił timeout/błąd — connect przebiega identycznie w obu przypadkach
     * (ARCHITECTURE.md §11.5/RT-12, zero regresji dla tenantów bez pluginów).
     */
    private final Map<String, Object> pluginDisplayData;

    /**
     * Opcjonalne ostrzeżenie z pluginu {@code PRE_CONTACT_CONNECT}
     * ({@code PreContactConnectResult.warning()}), np. "klient ma otwartą reklamację".
     * {@code null} gdy plugin nie zwrócił ostrzeżenia, brak pluginów, lub timeout/błąd.
     */
    private final String pluginWarning;

    // =========================================================================
    // Typy zdarzeń
    // =========================================================================

    public enum EventType {
        /** Przychodzące połączenie – agent jeszcze nie odebrał. */
        CALL_INCOMING,
        /**
         * Wychodzące połączenie kampanijne (outbound dialer) – klient jeszcze nie odebrał.
         *
         * <p>Routing key: {@code call.outbound} – pozwala frontendowi odróżnić
         * połączenie wychodzące od przychodzącego i wyświetlić właściwy UI agenta
         * (brak IVR, kierunek OUTBOUND, powiązana kampania).
         */
        CALL_OUTBOUND,
        /** Połączenie odebrane przez agenta. */
        CALL_ANSWERED,
        /** Połączenie zakończone (rozłączenie przez którąkolwiek ze stron). */
        CALL_HANGUP,
        /** Połączenie przekazane (blind lub attended transfer ukończony). */
        CALL_TRANSFERRED,

        /**
         * Druga noga attended transfer – konsultacja agenta z docelowym agentem/numerem.
         *
         * <p>Routing key: {@code call.transfer_consult} – pozwala IvrCallListener (zbindowanemu
         * do {@code call.incoming}) pominąć tę nogę, a RabbitToWebSocketRelay przekazać ją
         * jako osobny event do docelowego agenta z kontekstem transferu.
         *
         * <p>Metadane w {@link CallEvent#metadata}:
         * <ul>
         *   <li>{@code originalContactId} – UUID oryginalnego kontaktu (klienta)</li>
         *   <li>{@code originatingAgentId} – UUID agenta inicjującego konsultację</li>
         * </ul>
         */
        CALL_TRANSFER_CONSULT,

        /**
         * Konsultacja anulowana przez Agent1 przed bridge – Agent2 wraca do statusu AVAILABLE.
         *
         * <p>Routing key: {@code call.consult_cancelled}.
         * Publikowany przez {@link TwilioTelephonyAdapter#hangupCall} gdy rozłączana noga
         * ma {@code direction="CONSULTATION"}. Relay wysyła unicast do Agent2 (agentId),
         * który powinien wrócić do AVAILABLE bez ekranu ACW.
         */
        CALL_CONSULT_CANCELLED,

        /**
         * Konsultacja odebrana przez Agent2/numer zewnętrzny – Agent1 może teraz wykonać bridge.
         *
         * <p>Routing key: {@code call.consult_answered}.
         * Publikowany gdy noga konsultacyjna wchodzi w stan in-progress. Relay wysyła unicast
         * do Agent1 (inicjatora), który powinien aktywować przycisk "Przekaż".
         */
        CALL_CONSULT_ANSWERED,

        /** Attended transfer zakończony przez bridge – druga noga stała się pełnym kontaktem Agent2. */
        CALL_BRIDGE_COMPLETE;

        /**
         * Zwraca segment routing key dla tego eventu.
         * Przykład: {@code CALL_INCOMING} → {@code incoming}.
         */
        public String toRoutingKeySuffix() {
            // Usuwa prefix "CALL_" i zamienia na lowercase
            return name().replace("CALL_", "").toLowerCase();
        }
    }
}
