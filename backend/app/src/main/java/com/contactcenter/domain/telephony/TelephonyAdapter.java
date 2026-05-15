package com.contactcenter.domain.telephony;

import java.util.UUID;

/**
 * Interfejs adaptera telefonii – abstrakcja nad konkretnym providerem VoIP/CPaaS.
 *
 * <p>Wzorzec adaptera (GoF Adapter) pozwala podmieniać implementacje bez zmiany
 * kodu domenowego. Dostępne implementacje:
 * <ul>
 *   <li>{@code MockTelephonyAdapter} – symulacja w pamięci (dev/test)</li>
 *   <li>{@code TwilioTelephonyAdapter} – Twilio Programmable Voice REST API (przyszłość)</li>
 *   <li>{@code VonageTelephonyAdapter} – Vonage Voice API (przyszłość)</li>
 * </ul>
 *
 * <p>Wszystkie metody są synchroniczne z perspektywy wywołującego – asynchroniczne
 * zdarzenia providera docierają przez webhook ({@code POST /api/telephony/webhook})
 * i są publikowane jako {@link CallEvent} na RabbitMQ.
 *
 * <p>Wyjątki: metody rzucają {@link TelephonyException} gdy operacja nie może być
 * wykonana (np. połączenie już zakończone, błąd komunikacji z providerem).
 */
public interface TelephonyAdapter {

    /**
     * Inicjuje wychodzące połączenie telefoniczne.
     *
     * @param tenantId  identyfikator tenanta (wymagany dla multi-tenancy)
     * @param from      numer dzwoniącego w formacie E.164 (np. +48123456789)
     * @param to        numer docelowy w formacie E.164
     * @param agentId   agent inicjujący połączenie
     * @param queueId    UUID kolejki przypisanej do kampanii (nullable – ustawiany na rekordzie
     *                   contact aby RoutingService mógł przeprowadzić routing ACW; null gdy
     *                   połączenie wychodzące nie pochodzi z kampanii)
     * @param callbackId UUID powiązanego oddzwonienia (nullable – ustawiany gdy połączenie
     *                   jest realizacją {@code ScheduledCallback}; null dla pozostałych połączeń)
     * @return sesja połączenia ze statusem {@code RINGING}
     * @throws TelephonyException gdy nie można zainicjować połączenia
     */
    CallSession initiateCall(UUID tenantId, String from, String to, UUID agentId, UUID queueId, UUID callbackId);

    /**
     * Odbiera przychodzące połączenie.
     *
     * @param callId  identyfikator sesji połączenia
     * @param agentId UUID agenta odbierającego (wymagany do zestawienia audio w Twilio Conference)
     * @throws TelephonyException gdy połączenie nie istnieje lub jest w złym stanie
     */
    void answerCall(String callId, UUID agentId);

    /**
     * Rozłącza połączenie (hangup).
     *
     * <p>Idempotentne – wywołanie na już zakończonym połączeniu nie rzuca wyjątku.
     *
     * @param callId identyfikator sesji połączenia
     * @throws TelephonyException gdy połączenie nie istnieje
     */
    void hangupCall(String callId);

    /**
     * Wstrzymuje lub wznawia połączenie (hold/unhold).
     *
     * @param callId identyfikator sesji połączenia
     * @param hold   {@code true} = wstrzymaj, {@code false} = wznów
     * @throws TelephonyException gdy połączenie nie jest w stanie ACTIVE/ON_HOLD
     */
    void holdCall(String callId, boolean hold);

    /**
     * Wycisza lub odcisza mikrofon agenta.
     *
     * @param callId identyfikator sesji połączenia
     * @param mute   {@code true} = wycisz, {@code false} = odcisz
     * @throws TelephonyException gdy połączenie nie jest aktywne
     */
    void muteCall(String callId, boolean mute);

    /**
     * Przekazuje połączenie do innego numeru lub agenta.
     *
     * <p>Dla {@link TransferType#BLIND}: połączenie przekazywane natychmiast, sesja kończy się
     * po stronie inicjatora.
     * <p>Dla {@link TransferType#ATTENDED}: tworzona jest druga noga połączenia do {@code target};
     * po potwierdzeniu przez target wywołaj {@link #bridgeCalls(String, String)} aby połączyć obie nogi.
     *
     * @param callId       identyfikator pierwotnej sesji
     * @param target       numer docelowy w formacie E.164
     * @param transferType typ przekazania (BLIND lub ATTENDED)
     * @return nowa sesja drugiej nogi (dla ATTENDED) lub oryginalna sesja (dla BLIND)
     * @throws TelephonyException gdy połączenie nie jest w stanie ACTIVE/ON_HOLD
     */
    CallSession transferCall(String callId, String target, TransferType transferType);

    /**
     * Przekazuje połączenie do agenta, kolejki lub numeru telefonu.
     *
     * <p>Uogólnienie {@link #transferCall(String, String, TransferType)} wspierające
     * przekazanie do agenta ({@link TransferTargetType#AGENT}) i kolejki ({@link TransferTargetType#QUEUE})
     * poza dotychczasowym przekazaniem na numer telefonu ({@link TransferTargetType#PHONE}).
     *
     * <p>Przed wywołaniem należy wykonać {@link TransferRequest#validate()}.
     *
     * @param callId  identyfikator pierwotnej sesji
     * @param request żądanie przekazania z typem celu i odpowiednimi polami
     * @return nowa sesja drugiej nogi (dla ATTENDED) lub oryginalna sesja ze statusem TRANSFERRED (dla BLIND)
     * @throws TelephonyAdapter.TelephonyException gdy połączenie nie jest w stanie ACTIVE/ON_HOLD
     * @throws UnsupportedOperationException       gdy implementacja nie obsługuje danego targetType
     */
    CallSession initiateTransfer(String callId, TransferRequest request);

    /**
     * Łączy dwie nogi połączenia po attended transfer.
     *
     * <p>Wywołaj po tym jak agent potwierdził połączenie z {@code callId2}.
     * Obie sesje muszą być aktywne lub w stanie ON_HOLD.
     *
     * @param callId1 identyfikator pierwszej nogi (oryginalne połączenie z klientem)
     * @param callId2 identyfikator drugiej nogi (połączenie do docelowego agenta)
     * @throws TelephonyException gdy któraś z sesji nie istnieje lub jest w złym stanie
     */
    void bridgeCalls(String callId1, String callId2);

    /**
     * Pobiera aktualny stan sesji połączenia.
     *
     * @param callId identyfikator sesji połączenia
     * @return sesja połączenia z aktualnym statusem
     * @throws TelephonyException gdy połączenie nie istnieje
     */
    CallSession getCallSession(String callId);

    // =========================================================================
    // Typy i wyjątki
    // =========================================================================

    enum TransferType {
        /** Przekazanie ślepe – bez zapowiedzi, natychmiastowe. */
        BLIND,
        /** Przekazanie z zapowiedzi – agent rozmawia z odbiorcą przed przekazaniem. */
        ATTENDED
    }

    /**
     * Wyjątek rzucany przez adapter gdy operacja telefoniczna nie może być wykonana.
     */
    class TelephonyException extends RuntimeException {

        private final String callId;

        public TelephonyException(String message) {
            super(message);
            this.callId = null;
        }

        public TelephonyException(String callId, String message) {
            super(message);
            this.callId = callId;
        }

        public TelephonyException(String callId, String message, Throwable cause) {
            super(message, cause);
            this.callId = callId;
        }

        public String getCallId() {
            return callId;
        }
    }
}
