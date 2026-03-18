package com.contactcenter.domain.telephony;

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
