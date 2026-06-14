package com.contactcenter.domain.recording;

import com.contactcenter.domain.telephony.CallEvent;
import com.contactcenter.infrastructure.config.S3Properties;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Serwis zarządzający nagraniami rozmów telefonicznych (BE-010).
 *
 * <p><strong>Przepływ nagrywania:</strong>
 * <ol>
 *   <li>Zdarzenie {@code call.hangup} odbierane z RabbitMQ (exchange {@code cc.events})</li>
 *   <li>Pobranie pliku audio z tymczasowej lokalizacji lub wygenerowanie stub-a w trybie dev</li>
 *   <li>Upload do S3 z szyfrowaniem SSE-S3 (AES-256)</li>
 *   <li>Zapis ścieżki S3 do kolumny {@code recording_url} w tabeli {@code contact}</li>
 * </ol>
 *
 * <p><strong>Ścieżka S3:</strong> {@code /{tenantId}/{year}/{month}/{contactId}.mp3}
 *
 * <p><strong>Szyfrowanie:</strong> SSE-S3 (Server-Side Encryption z kluczami zarządzanymi przez S3/MinIO).
 * Alternatywnie można użyć SSE-C (klucze po stronie klienta) – zmień na {@code SSE_C} w PutObjectRequest.
 *
 * <p><strong>Tryb DEV:</strong> Gdy {@code CallEvent.metadata} nie zawiera klucza {@code audioFilePath},
 * serwis tworzy stub MP3 (pusty plik) do celów testowych. W produkcji MockTelephonyAdapter
 * powinien dostarczyć ścieżkę do rzeczywistego pliku audio.
 */
public interface RecordingService {

    /**
     * Odbiera zdarzenie {@code call.hangup} z kolejki RabbitMQ i inicjuje upload nagrania.
     *
     * <p>Metoda jest wywołana asynchronicznie przez Spring AMQP.
     * Błędy są logowane, ale nie rzucane ponownie (nagranie jest "best-effort" –
     * brak nagrania nie powinien blokować przetwarzania kolejki).
     *
     * @param event zdarzenie telefoniczne typu CALL_HANGUP
     */
    void onCallHangup(CallEvent event);

    /**
     * Zapisuje klucz S3 nagrania do tabeli {@code contact}.
     *
     * <p>Wywoływana przez {@link TwilioRecordingDownloadService} po pomyślnym uploadzie
     * nagrania do S3/MinIO. Wątek pochodzi z puli {@code @Async} i nie przechodzi przez
     * {@code TenantFilter}, dlatego TenantContext ustawiany jest ręcznie wzorcem
     * snapshot/restore (spójnym z resztą projektu).
     *
     * <p>Wzorzec snapshot/restore: tworzymy snapshot z tenantId przed wywołaniem
     * (caller dostarcza tenantId jako argument), przywracamy w wątku async i czyścimy
     * w {@code finally}.
     *
     * @param contactId    UUID kontaktu
     * @param tenantId     UUID tenanta (wymagany dla RLS)
     * @param s3Key        klucz obiektu w S3 (np. recordings/{tenantId}/{contactId}/{sid}.mp3)
     */
    void saveRecordingUrlToContact(UUID contactId, UUID tenantId, String s3Key);

    /**
     * Wysyła powiadomienie WebSocket do agenta o dostępności nagrania.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta
     */
    void notifyRecordingReady(UUID contactId, UUID tenantId);

    /**
     * Generuje presigned URL dla podanego klucza S3 z określonym TTL.
     *
     * <p>Wywoływana przez {@link com.contactcenter.domain.contact.ContactService#getRecordingUrl}
     * (BE-037), gdzie kontakt jest już załadowany z DB i znamy klucz S3.
     * Unika dodatkowego zapytania do bazy danych.
     *
     * @param s3Key klucz obiektu w S3 (np. tenantId/year/month/contactId.mp3)
     * @param ttl   czas ważności presigned URL
     * @return presigned URL jako String
     * @throws RecordingException gdy S3/MinIO jest niedostępny
     */
    String generatePresignedUrlForKey(String s3Key, Duration ttl);

    /**
     * Generuje presigned URL ważny przez {@link S3Properties#getPresignedUrlExpirationMinutes()} minut.
     *
     * <p>Nie zwraca pliku bezpośrednio – klient pobiera nagranie przez wygenerowany URL.
     * URL jest jednorazowy i wygasa po skonfigurowanym czasie.
     *
     * @param contactId UUID kontaktu
     * @param tenantId  UUID tenanta (weryfikacja RLS)
     * @return Optional z presigned URL lub empty jeśli nagranie nie istnieje
     */
    Optional<String> generatePresignedUrl(UUID contactId, UUID tenantId);

    /**
     * Uploaduje plik audio do S3 z szyfrowaniem SSE-S3 (AES-256).
     *
     * <p>SSE-S3: szyfrowanie zarządzane przez S3/MinIO – klucze są przechowywane
     * i rotowane po stronie storage. Alternatywa: SSE-C (klucze u klienta) wymaga
     * przekazywania klucza przy każdym żądaniu GET/PUT.
     *
     * @param s3Key    ścieżka w buckecie (np. /{tenantId}/{year}/{month}/{contactId}.mp3)
     * @param filePath lokalny plik do uploadowania
     */
    void uploadToS3(String s3Key, Path filePath);

    /**
     * Usuwa plik nagrania z S3.
     *
     * <p>Używane przez {@link RecordingRetentionJob} do fizycznego usuwania plików.
     *
     * @param s3Key ścieżka w buckecie
     */
    void deleteFromS3(String s3Key);

    /**
     * Buduje klucz S3 zgodnie z konwencją: {@code /{tenantId}/{year}/{month}/{contactId}.mp3}
     *
     * @param tenantId  UUID tenanta
     * @param contactId UUID kontaktu
     * @param timestamp czas zdarzenia (do wyznaczenia roku i miesiąca)
     * @return klucz S3
     */
    String buildS3Key(UUID tenantId, UUID contactId, Instant timestamp);
}
