package com.contactcenter.domain.recording;

import java.io.IOException;
import java.util.UUID;

/**
 * Serwis odpowiedzialny za pobieranie nagrań z Twilio i ich przechowywanie w S3/MinIO.
 *
 * <p><strong>Przepływ:</strong>
 * <ol>
 *   <li>Opcjonalnie: gdy brakuje callSid, pobierz contactId z nazwy konferencji przez Twilio API
 *       (Conference.fetcher) – wykonywane asynchronicznie, nie blokuje wątku webhooka.</li>
 *   <li>Pobierz plik MP3 z Twilio przez HTTP GET z Basic Auth (AccountSid:AuthToken)</li>
 *   <li>Streamuj plik tymczasowo na dysk lokalny (unika ładowania całości do pamięci)</li>
 *   <li>Uploaduj plik z dysku do S3/MinIO przez AWS SDK v2</li>
 *   <li>Usuń plik tymczasowy</li>
 * </ol>
 *
 * <p><strong>Obsługa błędów:</strong> metoda {@link #downloadAndStore} jest oznaczona
 * {@code @Async} – wyjątki są logowane i nie propagują do wątku webhook HTTP.
 * Wewnętrznie metoda jest podzielona na synchroniczną {@link #downloadAndStoreSync}
 * używaną w testach i asynchroniczną otoczkę.
 *
 * <p><strong>Naming S3:</strong>
 * {@code {tenantId}/{year}/{month}/{contactId}.mp3} – zgodnie z konwencją {@link RecordingService#buildS3Key}.
 */
public interface TwilioRecordingDownloadService {

    /**
     * Asynchronicznie pobiera nagranie z Twilio i zapisuje do S3/MinIO.
     *
     * <p>Gdy {@code contactId} jest null (nagranie konferencji bez callSid),
     * metoda samodzielnie pobiera contactId z nazwy konferencji przez Twilio API
     * ({@code Conference.fetcher(String)}).
     * Dzięki temu całe wywołanie Twilio REST API odbywa się w wątku {@code @Async},
     * nie blokując puli wątków Tomcata obsługujących webhooki.
     *
     * <p>Po pomyślnym uploadzie aktualizuje {@code recording_url} w tabeli {@code contact}
     * delegując do {@link RecordingService#saveRecordingUrlToContact}.
     *
     * <p>Metoda jest {@code @Async} – webhook HTTP zwraca 204 natychmiast,
     * a faktyczne pobieranie dzieje się w tle (wątek {@code cc-async-*}).
     *
     * @param twilioRecordingUrl URL nagrania z Twilio (z dopisanym .mp3)
     * @param recordingSid       Twilio Recording SID (do nazwy obiektu w S3)
     * @param callSid            Twilio Call SID (CA...) – może być null dla nagrań konferencji
     * @param conferenceSid      Twilio Conference SID (CF...) – używany gdy callSid jest null
     * @param contactId          UUID kontaktu lub null gdy wymaga rozwiązania przez conferenceSid
     * @param tenantId           UUID tenanta (do nazwy obiektu i kontekstu DB)
     */
    void downloadAndStore(
            String twilioRecordingUrl,
            String recordingSid,
            String callSid,
            String conferenceSid,
            UUID contactId,
            UUID tenantId);

    /**
     * Synchroniczna implementacja pobierania i uploadu nagrania.
     *
     * <p>Użycie pliku tymczasowego zamiast buforowania w pamięci pozwala obsłużyć
     * nagrania o dowolnej długości bez ryzyka OOM. Plik jest usuwany w bloku
     * {@code finally} niezależnie od sukcesu/błędu.
     *
     * @param twilioRecordingUrl pełny URL do pliku MP3 w Twilio
     * @param recordingSid       Recording SID (unikalny identyfikator nagrania Twilio)
     * @param contactId          UUID kontaktu
     * @param tenantId           UUID tenanta
     * @throws IOException      przy błędzie I/O (pobieranie lub plik tymczasowy)
     * @throws InterruptedException przy przerwaniu wątku HTTP
     */
    void downloadAndStoreSync(
            String twilioRecordingUrl,
            String recordingSid,
            UUID contactId,
            UUID tenantId) throws IOException, InterruptedException;

    /**
     * Buduje klucz S3 delegując do {@link RecordingService#buildS3Key}.
     * Format: {@code {tenantId}/{year}/{month}/{contactId}.mp3}
     */
    String buildS3Key(UUID tenantId, UUID contactId);
}
