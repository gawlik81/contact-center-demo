package com.contactcenter.domain.telephony;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Getter;
import lombok.With;
import lombok.extern.jackson.Jacksonized;

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
/**
 * Adnotacje JSON wymagane do serializacji/deserializacji przez Jackson w Redis.
 * {@code @Jacksonized} generuje kompatybilny z Jackson builder (wymagany przy @Builder).
 * {@code @JsonIgnoreProperties(ignoreUnknown = true)} zabezpiecza deserializację
 * gdy schemat klasy zmieni się między wersjami aplikacji (stara sesja w Redis).
 */
@Getter
@Builder
@With
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
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

    /**
     * Moment faktycznego odebrania przez klienta – ustawiany wyłącznie gdy Twilio
     * wysyła {@code StatusCallback} z {@code CallStatus=in-progress} dla połączenia OUTBOUND.
     *
     * <p>Różni się od {@link #answeredAt}, który jest ustawiany przez {@code answerCall()}
     * gdy agent kliknie "Odbierz" – nawet zanim klient fizycznie podniesie słuchawkę.
     * {@code clientAnsweredAt != null} jest warunkiem koniecznym do uznania rozmowy
     * za zakończoną z wynikiem {@code COMPLETED} (a nie {@code NOT_REACHED}).
     *
     * <p>Null dla połączeń INBOUND (semantyka jest inna – klient już dzwoni)
     * i dla połączeń OUTBOUND gdzie klient nie odebrał.
     */
    private final Instant clientAnsweredAt;

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

    /**
     * Twilio Call SID nogi agenta (CA_...) tworzonej przez {@code dialAgentIntoConference()}.
     *
     * <p>Wymagany do rozłączenia nogi agenta przez REST API w {@code hangupCall()}.
     * Null dopóki agent nie dołączył do konferencji lub gdy adapter jest mock.
     */
    private final String agentCallSid;

    /**
     * Nazwa konferencji Twilio, do której agent jest podłączony.
     * Ustawiana w dialAgentIntoConference() i przy tworzeniu nogi konsultacji.
     * Nie zmienia się po updateSessionContact() – pozwala na prawidłowe zarządzanie
     * konferencją po zmianie logicznego contactId.
     */
    private final String conferenceName;

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
