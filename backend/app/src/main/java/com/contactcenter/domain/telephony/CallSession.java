package com.contactcenter.domain.telephony;

import lombok.Builder;
import lombok.Getter;
import lombok.With;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain object reprezentujący sesję połączenia telefonicznego.
 *
 * <p>Nie jest encją JPA – stan przechowywany w pamięci przez MockTelephonyAdapter
 * lub po stronie providera VoIP (Twilio, Vonage itp.) w implementacjach produkcyjnych.
 *
 * <p>Immutable (Lombok @With do tworzenia zmodyfikowanych kopii).
 */
@Getter
@Builder
@With
public class CallSession {

    /** Unikalny identyfikator sesji połączenia. */
    private final String callId;

    /** Tenant, do którego należy połączenie. */
    private final UUID tenantId;

    /** Agent obsługujący połączenie (może być null dla połączeń przychodzących przed przydziałem). */
    private final UUID agentId;

    /**
     * UUID rekordu kontaktu w tabeli {@code contact} powiązanego z tą sesją.
     *
     * <p>Wypełniany przez {@link MockTelephonyAdapter} po persystencji rekordu w DB.
     * Pozwala adapterowi na aktualizację statusu kontaktu przy zmianach stanu sesji
     * (np. COMPLETED przy hangup). Null gdy rekord contact nie został utworzony
     * (błąd DB lub real provider).
     */
    private final UUID contactId;

    /** Numer dzwoniącego (format E.164, np. +48123456789). */
    private final String from;

    /** Numer docelowy (format E.164). */
    private final String to;

    /** Aktualny status sesji. */
    private final CallStatus status;

    /** Moment inicjalizacji / odebrania połączenia przychodzącego. */
    private final Instant startedAt;

    /** Moment odebrania przez agenta (null gdy jeszcze nie odebrane). */
    private final Instant answeredAt;

    /** Moment zakończenia połączenia (null gdy aktywne). */
    private final Instant endedAt;

    /**
     * Kierunek połączenia: {@code "OUTBOUND"} dla wychodzących (dialer/agent initiates),
     * {@code "INBOUND"} dla przychodzących (customer calls in).
     *
     * <p>Null dla sesji odtworzonych z DB lub połączeń zarejestrowanych przed tym polem –
     * traktuj null jako INBOUND (bezpieczny fallback zachowujący dotychczasowe zachowanie).
     */
    private final String direction;

    // =========================================================================
    // Enum statusów
    // =========================================================================

    public enum CallStatus {
        /** Połączenie dzwoni (jeszcze nie odebrane). */
        RINGING,
        /** Połączenie aktywne (w trakcie rozmowy). */
        ACTIVE,
        /** Połączenie wstrzymane (hold). */
        ON_HOLD,
        /** Połączenie przekazane. */
        TRANSFERRED,
        /** Połączenie zakończone. */
        ENDED
    }
}
