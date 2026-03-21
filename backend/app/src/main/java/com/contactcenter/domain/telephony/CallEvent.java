package com.contactcenter.domain.telephony;

import com.contactcenter.domain.service.CustomerCliResult;
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

    // =========================================================================
    // Typy zdarzeń
    // =========================================================================

    public enum EventType {
        /** Przychodzące połączenie – agent jeszcze nie odebrał. */
        CALL_INCOMING,
        /** Połączenie odebrane przez agenta. */
        CALL_ANSWERED,
        /** Połączenie zakończone (rozłączenie przez którąkolwiek ze stron). */
        CALL_HANGUP,
        /** Połączenie przekazane (blind lub attended transfer ukończony). */
        CALL_TRANSFERRED;

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
