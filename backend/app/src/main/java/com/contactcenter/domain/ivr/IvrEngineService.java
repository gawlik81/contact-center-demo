package com.contactcenter.domain.ivr;

import com.contactcenter.domain.routing.IncomingCallRoutingService;

import java.util.UUID;

/**
 * Silnik IVR – orkiestruje przepływ połączenia przez drzewo IVR.
 *
 * <p>Odpowiedzialności:
 * <ul>
 *   <li>Startowanie sesji IVR przy odebraniu przychodzącego połączenia</li>
 *   <li>Przetwarzanie wejścia DTMF od dzwoniącego</li>
 *   <li>Wykonywanie węzłów (PLAY_AUDIO, MENU, COLLECT_DTMF, QUEUE_TRANSFER, HANGUP)</li>
 *   <li>Cache'owanie audio TTS w Redis (klucz {@code ivr:tts:{md5}} TTL 24h)</li>
 *   <li>Zarządzanie sesją w Redis (klucz {@code ivr:session:{callId}} TTL 30min)</li>
 *   <li>Fallback na domyślną kolejkę przy błędzie węzła</li>
 * </ul>
 *
 * <p>Sesja IVR w Redis:
 * <ul>
 *   <li>Tworzona przy {@link #startIvrSession}</li>
 *   <li>Aktualizowana przy każdym przejściu do nowego węzła</li>
 *   <li>Usuwana przy HANGUP lub QUEUE_TRANSFER</li>
 *   <li>TTL: 30 minut (auto-expire dla porzuconych połączeń)</li>
 * </ul>
 */
public interface IvrEngineService {

    /**
     * Startuje sesję IVR dla przychodzącego połączenia.
     *
     * <p>Przepływ:
     * <ol>
     *   <li>Pobiera aktywne drzewo IVR dla tenanta</li>
     *   <li>Tworzy sesję w Redis z węzłem wejściowym</li>
     *   <li>Wykonuje węzeł wejściowy</li>
     * </ol>
     *
     * <p>Gdy brak aktywnego drzewa IVR: wywołuje fallback do domyślnej kolejki.
     *
     * @param callId   identyfikator sesji połączenia
     * @param tenantId UUID tenanta
     * @param ivrId    UUID konkretnego drzewa IVR (null = użyj aktywnego dla tenanta)
     */
    void startIvrSession(String callId, UUID tenantId, UUID ivrId);

    /**
     * Przetwarza wejście DTMF od dzwoniącego.
     *
     * <p>Rozróżnia dwa tryby pracy:
     * <ul>
     *   <li><b>COLLECT_DTMF</b> – gdy sesja jest w trakcie zbierania cyfr;
     *       akumuluje cyfry w buforze, kończy zbieranie po {@code finishOnKey} lub {@code maxDigits}</li>
     *   <li><b>MENU / domyślny</b> – szuka opcji w aktualnym węźle po pojedynczym klawiszu DTMF</li>
     * </ul>
     *
     * @param callId  identyfikator sesji połączenia
     * @param dtmfKey klawisz DTMF ("0"–"9", "*", "#", "timeout", "no-input")
     */
    void handleDtmfInput(String callId, String dtmfKey);

    /**
     * Startuje sesję IVR w trybie TwiML dla konkretnego drzewa IVR i zwraca TwiML.
     *
     * <p>Wariant z jawnym {@code ivrTreeId} – używany przez silnik routingu połączeń przychodzących
     * ({@link IncomingCallRoutingService}) gdy reguła harmonogramu wskazuje konkretne drzewo IVR
     * zamiast domyślnego aktywnego drzewa tenanta.
     *
     * <p>Gdy {@code ivrTreeId} jest {@code null}, używa aktywnego drzewa tenanta.
     *
     * @param callId    Twilio Call SID
     * @param tenantId  UUID tenanta
     * @param baseUrl   publiczny bazowy URL aplikacji
     * @param contactId UUID rekordu contact w DB (może być null)
     * @param ivrTreeId UUID konkretnego drzewa IVR (null = użyj aktywnego dla tenanta)
     * @return TwiML string gotowy do zwrotu jako {@code application/xml}
     */
    String startIvrSessionAndBuildTwiml(String callId, UUID tenantId, String baseUrl, UUID contactId,
                                         UUID ivrTreeId);

    /**
     * Kieruje przychodzące połączenie bezpośrednio do kolejki bez przechodzenia przez IVR.
     *
     * <p>Odpowiednik węzła {@code QUEUE_TRANSFER} w IVR, ale synchroniczny – zwraca TwiML
     * bezpośrednio. Używany gdy reguła routingu wskazuje kolejkę bez skonfigurowanego IVR.
     *
     * <p>Przepływ:
     * <ol>
     *   <li>Weryfikuje istnienie kolejki w bazie danych</li>
     *   <li>Aktualizuje {@code contact.queue_id} w DB (wymagane dla retry-routingu agentów)</li>
     *   <li>Publikuje event kolejkowania kontaktu do RabbitMQ</li>
     *   <li>Zwraca TwiML kierujący dzwoniącego do konferencji Twilio (czeka na agenta)</li>
     * </ol>
     *
     * @param callSid   Twilio Call SID
     * @param tenantId  UUID tenanta
     * @param queueId   UUID kolejki (z reguły routingu)
     * @param contactId UUID rekordu contact (może być null jeśli nie udało się utworzyć)
     * @param baseUrl   publiczny bazowy URL aplikacji
     * @return TwiML string gotowy do zwrotu jako {@code application/xml}
     */
    String routeDirectlyToQueue(String callSid, UUID tenantId, UUID queueId, UUID contactId, String baseUrl);

    /**
     * Przetwarza wejście DTMF i zwraca TwiML dla następnego węzła IVR.
     *
     * <p>Wywołaj z DTMF action URL (endpoint {@code /dtmf}).
     * Metoda wewnętrznie wywołuje {@link #handleDtmfInput} (aktualizacja stanu sesji),
     * następnie odczytuje zaktualizowaną sesję i buduje TwiML dla nowego bieżącego węzła.
     *
     * @param callId    Twilio Call SID
     * @param dtmfInput naciśnięte klawisze DTMF (np. "1", "2", "#")
     * @param tenantId  UUID tenanta (potrzebny do budowania action URL)
     * @param baseUrl   publiczny bazowy URL aplikacji
     * @return TwiML string gotowy do zwrotu jako {@code application/xml}
     */
    String handleDtmfAndBuildTwiml(String callId, String dtmfInput, UUID tenantId, String baseUrl);

    /**
     * Obsługuje callback nagrania voicebota i zwraca TwiML dla kolejnego węzła IVR.
     *
     * <p>Wywoływany przez endpoint {@code POST /voicebot-recording} po tym jak Twilio
     * zakończy nagranie wypowiedzi dzwoniącego. Przepływ:
     * <ol>
     *   <li>Ładuje sesję IVR z Redis.</li>
     *   <li>Pobiera plik WAV z URL nagrania Twilio (Basic Auth: AccountSid:AuthToken).</li>
     *   <li>Koduje bajty do Base64 i zapisuje jako zmienną sesji {@code voicebot_audio_base64}.</li>
     *   <li>Wywołuje silnik voicebota, który kontaktuje się z serwisem Python ASR+NLU.</li>
     *   <li>Odczytuje zaktualizowaną sesję i zwraca TwiML dla nowego bieżącego węzła.</li>
     * </ol>
     *
     * <p>Graceful degradation: gdy brak {@code recordingUrl}, brak sesji, błąd pobierania
     * lub wyjątek – metoda zwraca fallback TwiML i kieruje do domyślnej kolejki.
     * Twilio nie powiadamia o błędach HTTP 5xx z action URL – dlatego każdy wyjątek musi
     * być obsłużony tutaj i zwrócić prawidłowy TwiML.
     *
     * @param callId            Twilio Call SID
     * @param tenantId          UUID tenanta
     * @param baseUrl           publiczny bazowy URL aplikacji
     * @param recordingUrl      URL nagrania Twilio (bez rozszerzenia lub z .wav/.mp3)
     * @param recordingDuration czas trwania nagrania w sekundach (informacyjny)
     * @return TwiML string gotowy do zwrotu jako {@code application/xml}
     */
    String handleVoicebotRecordingAndBuildTwiml(String callId, UUID tenantId, String baseUrl,
                                                 String recordingUrl, String recordingDuration);
}
