package com.contactcenter.domain.voicebot;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Optional;

/**
 * Klient HTTP do serwisu Python voicebot (BE-014).
 *
 * <p>Implementacja ({@code VoicebotClientImpl}) aktywowana tylko gdy
 * {@code voicebot.enabled=true} (domyślnie false).
 * Przy błędzie – loguje WARN i zwraca {@link Optional#empty()}, żeby silnik IVR
 * mógł zastosować graceful degradation (fallback node).
 *
 * <p>Timeout: connectTimeout=1s, readTimeout=3s (wymaganie: p95 &lt; 2s dla 5s audio).
 * Używa java.net.http.HttpClient zamiast RestClient – nie wymaga negocjacji Content-Type.
 */
public interface VoicebotClient {

    // DTO do komunikacji z serwisem Python
    record TurnRequest(
            @JsonProperty("session_id")   String sessionId,
            @JsonProperty("tenant_id")    String tenantId,
            @JsonProperty("contact_id")   String contactId,
            @JsonProperty("audio_base64") String audioBase64,
            @JsonProperty("audio_format") String audioFormat,
            @JsonProperty("turn_number")  int turnNumber
    ) {}

    record TurnResponse(
            @JsonProperty("session_id")           String sessionId,
            @JsonProperty("transcript")           String transcript,
            @JsonProperty("intent")               String intent,
            @JsonProperty("confidence")           double confidence,
            @JsonProperty("escalate")             boolean escalate,
            @JsonProperty("escalation_reason")    String escalationReason,
            @JsonProperty("full_transcript")      List<String> fullTranscript,
            @JsonProperty("response_text")        String responseText,
            @JsonProperty("continue_conversation") boolean continueConversation
    ) {}

    record TranscribeRequest(
            @JsonProperty("audio_base64") String audioBase64,
            @JsonProperty("audio_format") String audioFormat
    ) {}

    record TranscribeResponse(
            @JsonProperty("transcript")  String transcript,
            @JsonProperty("language")    String language,
            @JsonProperty("confidence")  double confidence
    ) {}

    /**
     * Przetwarza jedną turę konwersacji.
     *
     * @param request dane audio + kontekst sesji
     * @return odpowiedź voicebota lub empty() gdy serwis niedostępny lub błąd
     */
    Optional<TurnResponse> processTurn(TurnRequest request);

    /**
     * Transkrybuje nagranie audio przez Whisper (endpoint {@code POST /ai/transcribe}).
     *
     * <p>Używane przez {@code TwilioRecordingDownloadService} po zapisaniu MP3 do S3,
     * żeby zapisać transkrypcję w tabeli {@code contact_transcription}.
     * Timeout ustawiony na 60s – Whisper na dużym pliku może potrzebować więcej czasu
     * niż przy strumieniowaniu w czasie rzeczywistym.
     *
     * <p>Przy błędzie HTTP lub wyjątku sieciowym zwraca {@link Optional#empty()} –
     * caller traktuje brak transkryptu jako graceful degradation (nagranie jest już w S3).
     *
     * @param audioBytes  surowe bajty pliku MP3/WAV
     * @param audioFormat format pliku: "mp3" lub "wav"
     * @return Optional z pełną odpowiedzią Whisper (transcript + language) lub empty() przy błędzie
     */
    Optional<TranscribeResponse> transcribeFull(byte[] audioBytes, String audioFormat);

    /**
     * Transkrybuje nagranie audio – wersja uproszczona zwracająca tylko tekst.
     *
     * @deprecated Używaj {@link #transcribeFull} żeby uzyskać też wykryty język.
     *             Pozostawiona dla kompatybilności z istniejącymi callsitami.
     */
    @Deprecated
    Optional<String> transcribe(byte[] audioBytes, String audioFormat);

    /**
     * Usuwa sesję voicebota po hangup.
     * Błędy są logowane ale nie propagowane.
     *
     * @param sessionId identyfikator sesji do usunięcia
     */
    void deleteSession(String sessionId);
}
