package com.contactcenter.domain.websocket;

import com.contactcenter.domain.service.CustomerCliResult;
import com.contactcenter.domain.telephony.CallEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * DTO eventu WebSocket wysyłanego do klientów przez STOMP.
 *
 * <p>Wszystkie eventy domenowe są serializowane do tej struktury przed wysłaniem
 * przez {@link WebSocketEventBroadcaster}. Klient Angular deserializuje ten format.
 *
 * <p>Typy eventów (pole {@code eventType}):
 * <ul>
 *   <li>{@code CALL_INCOMING} – przychodzące połączenie (→ agentowi)</li>
 *   <li>{@code CALL_OUTBOUND} – wychodzące połączenie kampanijne zainicjowane przez dialer (→ agentowi)</li>
 *   <li>{@code CALL_ANSWERED} – połączenie odebrane</li>
 *   <li>{@code CALL_HANGUP} – połączenie zakończone</li>
 *   <li>{@code AGENT_STATUS_CHANGED} – zmiana statusu agenta (→ supervisorom)</li>
 *   <li>{@code CONTACT_ASSIGNED} – kontakt przydzielony agentowi (→ agentowi)</li>
 *   <li>{@code QUEUE_UPDATE} – aktualizacja stanu kolejki (→ supervisorom)</li>
 * </ul>
 *
 * @param eventType typ eventu (jedna ze stałych powyżej)
 * @param tenantId  UUID tenanta – do weryfikacji po stronie klienta
 * @param payload   dane domenowe specyficzne dla typu eventu
 * @param timestamp czas wystąpienia zdarzenia
 */
public record WebSocketEvent(
        String eventType,
        UUID tenantId,
        Object payload,
        Instant timestamp
) {

    private static final Logger log = LoggerFactory.getLogger(WebSocketEvent.class);

    // =========================================================================
    // Stałe typów eventów
    // =========================================================================

    public static final String TYPE_CALL_INCOMING         = "CALL_INCOMING";
    public static final String TYPE_CALL_OUTBOUND         = "CALL_OUTBOUND";
    public static final String TYPE_CALL_ANSWERED         = "CALL_ANSWERED";
    public static final String TYPE_CALL_HANGUP           = "CALL_HANGUP";
    public static final String TYPE_AGENT_STATUS_CHANGED  = "AGENT_STATUS_CHANGED";
    public static final String TYPE_CONTACT_ASSIGNED      = "CONTACT_ASSIGNED";
    public static final String TYPE_QUEUE_UPDATE          = "QUEUE_UPDATE";
    public static final String TYPE_PONG                  = "PONG";

    // =========================================================================
    // Fabryczne metody statyczne
    // =========================================================================

    /**
     * Tworzy event CALL_INCOMING z domenowego CallEvent.
     *
     * <p>Wysyłany do konkretnego agenta (unicast) gdy nadchodzi połączenie.
     * Payload używa pól zgodnych z interfejsem {@code CallIncomingPayload} po stronie Angular:
     * {@code contactId}, {@code customerName}, {@code customerPhone}, {@code queueName}.
     */
    public static WebSocketEvent callIncoming(CallEvent callEvent) {
        return new WebSocketEvent(
                TYPE_CALL_INCOMING,
                callEvent.getTenantId(),
                CallIncomingPayload.from(callEvent),
                callEvent.getTimestamp() != null ? callEvent.getTimestamp() : Instant.now()
        );
    }

    /**
     * Tworzy event CALL_OUTBOUND z domenowego CallEvent.
     *
     * <p>Wysyłany do konkretnego agenta (unicast) gdy inicjowane jest połączenie wychodzące
     * (kampanijne lub manualne). Payload jest tożsamy z {@link CallIncomingPayload} —
     * agent widzi numer docelowy klienta w polu {@code customerPhone} i może go odebrać
     * w softphonie gdy telefon klienta zacznie dzwonić.
     */
    public static WebSocketEvent callOutbound(CallEvent callEvent) {
        return new WebSocketEvent(
                TYPE_CALL_OUTBOUND,
                callEvent.getTenantId(),
                CallIncomingPayload.from(callEvent),
                callEvent.getTimestamp() != null ? callEvent.getTimestamp() : Instant.now()
        );
    }

    /**
     * Tworzy event CALL_ANSWERED z domenowego CallEvent.
     */
    public static WebSocketEvent callAnswered(CallEvent callEvent) {
        return new WebSocketEvent(
                TYPE_CALL_ANSWERED,
                callEvent.getTenantId(),
                CallPayload.from(callEvent),
                callEvent.getTimestamp() != null ? callEvent.getTimestamp() : Instant.now()
        );
    }

    /**
     * Tworzy event CALL_HANGUP z domenowego CallEvent.
     */
    public static WebSocketEvent callHangup(CallEvent callEvent) {
        return new WebSocketEvent(
                TYPE_CALL_HANGUP,
                callEvent.getTenantId(),
                CallPayload.from(callEvent),
                callEvent.getTimestamp() != null ? callEvent.getTimestamp() : Instant.now()
        );
    }

    /**
     * Tworzy event zmiany statusu agenta.
     *
     * @param tenantId UUID tenanta
     * @param agentId  UUID agenta
     * @param newStatus nowy status (np. AVAILABLE, BUSY, OFFLINE)
     */
    public static WebSocketEvent agentStatusChanged(UUID tenantId, UUID agentId, String newStatus) {
        return new WebSocketEvent(
                TYPE_AGENT_STATUS_CHANGED,
                tenantId,
                new AgentStatusPayload(agentId.toString(), newStatus),
                Instant.now()
        );
    }

    /**
     * Tworzy event przydzielenia kontaktu do agenta.
     *
     * @param tenantId           UUID tenanta
     * @param agentId            UUID agenta
     * @param contactId          UUID kontaktu
     * @param channel            kanał kontaktu (np. "PHONE", "CHAT", "EMAIL") – mapowany jako {@code type} w Angular
     * @param customerName       nazwa wyświetlana klienta (adres email lub numer telefonu)
     * @param customerIdentifier identyfikator klienta (adres email lub numer telefonu)
     * @param queueName          nazwa kolejki
     * @param customerId         UUID klienta jako String (nullable – gdy klient nieznany)
     */
    public static WebSocketEvent contactAssigned(UUID tenantId, UUID agentId,
                                                  UUID contactId, String channel,
                                                  String customerName, String customerIdentifier,
                                                  String queueName, String customerId) {
        return new WebSocketEvent(
                TYPE_CONTACT_ASSIGNED,
                tenantId,
                new ContactAssignedPayload(agentId.toString(), contactId.toString(), channel,
                        customerName, customerIdentifier, queueName, customerId),
                Instant.now()
        );
    }

    /**
     * Tworzy event aktualizacji stanu kolejki.
     *
     * @param tenantId     UUID tenanta
     * @param queueId      UUID kolejki
     * @param waitingCount liczba oczekujących kontaktów
     * @param agentsAvail  liczba dostępnych agentów
     */
    public static WebSocketEvent queueUpdate(UUID tenantId, UUID queueId,
                                              int waitingCount, int agentsAvail) {
        return new WebSocketEvent(
                TYPE_QUEUE_UPDATE,
                tenantId,
                new QueueUpdatePayload(queueId.toString(), waitingCount, agentsAvail),
                Instant.now()
        );
    }

    /**
     * Tworzy event QUEUE_UPDATE z listą oczekujących kontaktów – wysyłany do agentów.
     *
     * @param tenantId UUID tenanta
     * @param items    lista oczekujących kontaktów
     */
    public static WebSocketEvent queueAgentUpdate(UUID tenantId, List<QueueItemDto> items) {
        return new WebSocketEvent(
                TYPE_QUEUE_UPDATE,
                tenantId,
                new QueueAgentUpdatePayload(items),
                Instant.now()
        );
    }

    /**
     * Tworzy event PONG w odpowiedzi na PING od klienta.
     *
     * @param tenantId UUID tenanta bieżącej sesji
     */
    public static WebSocketEvent pong(UUID tenantId) {
        return new WebSocketEvent(TYPE_PONG, tenantId, null, Instant.now());
    }

    // =========================================================================
    // Payload records – niezmienne DTO dla poszczególnych typów eventów
    // =========================================================================

    /**
     * Payload dla eventu CALL_INCOMING – pola zgodne z interfejsem Angular {@code CallIncomingPayload}.
     *
     * <p>Mapowanie z domenowego {@link CallEvent}:
     * <ul>
     *   <li>{@code contactId} ← {@code callEvent.getCallId()} (alias; call jest traktowany jako kontakt)</li>
     *   <li>{@code customerPhone} ← {@code callEvent.getFrom()} (numer dzwoniącego)</li>
     *   <li>{@code customerName} ← imię + nazwisko z CLI lookup, lub "Nieznany ({from})" gdy null</li>
     *   <li>{@code customerId} ← UUID klienta z CLI lookup (null gdy nieznany numer)</li>
     *   <li>{@code lastContacts} ← ostatnie 3 kontakty klienta (null gdy nieznany numer)</li>
     *   <li>{@code queueName} ← {@code callEvent.getMetadata().get("queueName")} lub pusty string</li>
     * </ul>
     */
    public record CallIncomingPayload(
            String contactId,
            String customerName,
            String customerPhone,
            String queueName,
            String customerId,
            List<CustomerCliResult.ContactSummary> lastContacts
    ) {
        public static CallIncomingPayload from(CallEvent callEvent) {
            // Numer telefonu klienta: dla OUTBOUND klientem jest strona docelowa (to),
            // dla INCOMING klientem jest dzwoniący (from).
            String customerPhone = (callEvent.getEventType() == CallEvent.EventType.CALL_OUTBOUND)
                    ? callEvent.getTo()
                    : callEvent.getFrom();

            String queueName = callEvent.getMetadata() != null
                    ? callEvent.getMetadata().getOrDefault("queueName", "")
                    : "";

            CustomerCliResult cli = callEvent.getCustomerInfo();
            String customerName;
            String customerId = null;
            List<CustomerCliResult.ContactSummary> lastContacts = null;

            if (cli != null) {
                // Znany klient – buduj wyświetlaną nazwę
                String firstName = cli.firstName() != null ? cli.firstName() : "";
                String lastName  = cli.lastName()  != null ? cli.lastName()  : "";
                customerName = (firstName + " " + lastName).trim();
                if (customerName.isBlank()) {
                    customerName = "Nieznany (" + (customerPhone != null ? customerPhone : "?") + ")";
                }
                customerId   = cli.customerId() != null ? cli.customerId().toString() : null;
                lastContacts = cli.lastContacts();
            } else {
                customerName = "Nieznany (" + (customerPhone != null ? customerPhone : "?") + ")";
            }

            // Gdy dostępny contactId z DB – użyj go jako identyfikatora kontaktu,
            // żeby frontend mógł wywoływać REST API (np. PATCH /api/contacts/{contactId}/disposition).
            // Fallback na callId gdy persistMockContact() zawiodło – powoduje 422 przy setDisposition.
            // Logujemy ERROR żeby ułatwić diagnozę (np. brak rekordu contact w DB).
            String contactId;
            if (callEvent.getContactId() != null) {
                contactId = callEvent.getContactId().toString();
            } else {
                contactId = callEvent.getCallId();
                log.error("[WS][CallIncoming] contactId IS NULL dla callId={}. " +
                        "persistMockContact() zawiodło – frontend otrzyma callId zamiast UUID. " +
                        "PATCH /api/contacts/{}/disposition zwróci 422. Sprawdź logi MockTelephonyAdapter.",
                        callEvent.getCallId(), callEvent.getCallId());
            }

            return new CallIncomingPayload(
                    contactId,
                    customerName,
                    customerPhone,
                    queueName,
                    customerId,
                    lastContacts
            );
        }
    }

    /**
     * Payload dla eventów związanych z połączeniami telefonicznymi.
     */
    public record CallPayload(
            String callId,
            String agentId,
            String from,
            String to,
            String eventType
    ) {
        public static CallPayload from(CallEvent callEvent) {
            return new CallPayload(
                    callEvent.getCallId(),
                    callEvent.getAgentId() != null ? callEvent.getAgentId().toString() : null,
                    callEvent.getFrom(),
                    callEvent.getTo(),
                    callEvent.getEventType() != null ? callEvent.getEventType().name() : null
            );
        }
    }

    /**
     * Payload dla eventów zmiany statusu agenta.
     */
    public record AgentStatusPayload(
            String agentId,
            String status
    ) {}

    /**
     * Payload dla eventów przydzielenia kontaktu.
     *
     * <p>Pole {@code type} odpowiada interfejsowi Angular {@code ContactAssignedPayload.type}
     * (wartości: "PHONE", "CHAT", "EMAIL") i pochodzi z pola {@code channel} encji Contact.
     *
     * <p>Pole {@code customerId} jest nullable – ustawiane gdy kontakt ma przypisanego klienta
     * (po naprawie Problemu 1: EmailContactCreator lookup po emailu). Używane przez frontend
     * do wyświetlenia panelu klienta bez osobnego wywołania API.
     */
    public record ContactAssignedPayload(
            String agentId,
            String contactId,
            String type,
            String customerName,
            String customerIdentifier,
            String queueName,
            String customerId
    ) {}

    /**
     * Payload dla eventów aktualizacji kolejki.
     */
    public record QueueUpdatePayload(
            String queueId,
            int waitingCount,
            int agentsAvailable
    ) {}

    /**
     * Payload dla eventów QUEUE_UPDATE wysyłanych do agentów – pełna lista oczekujących kontaktów.
     */
    public record QueueAgentUpdatePayload(
            List<QueueItemDto> items
    ) {}

    /**
     * DTO pojedynczego elementu kolejki widocznego przez agenta.
     *
     * <p>Pole {@code type} odpowiada wartościom Angular {@code ContactType}:
     * {@code PHONE}, {@code EMAIL}, {@code SOCIAL}.
     * Kanały {@code SOCIAL_FACEBOOK}, {@code SOCIAL_INSTAGRAM}, {@code SOCIAL_WHATSAPP}
     * są mapowane do {@code SOCIAL} przez {@code RoutingService.mapChannelToType()}.
     */
    public record QueueItemDto(
            String id,
            String type,
            String customerName,
            String customerIdentifier,
            Instant waitingSince,
            String queueName,
            int priority
    ) {}
}
