package com.contactcenter.domain.email;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Serwis przechowywania załączników email w S3-compatible storage.
 *
 * <p>Schemat kluczy S3:
 * <ul>
 *   <li>Inbound (IMAP): {@code email-attachments/{tenantId}/{messageId}/{encodedFilename}}</li>
 *   <li>Pending (upload agenta): {@code email-attachments/{tenantId}/pending/{uuid}/{encodedFilename}}</li>
 * </ul>
 *
 * <p>Używa tego samego bucketu co nagrania ({@code contact-center-recordings}).
 */
public interface EmailAttachmentStorageService {

    /**
     * Przechowuje bajty załącznika w S3 i zwraca klucz S3.
     *
     * @param tenantId    UUID tenanta
     * @param messageId   UUID wiadomości (dla inbound)
     * @param filename    nazwa pliku
     * @param contentType MIME type
     * @param data        bajty załącznika
     * @return klucz S3 pod którym zapisano plik
     */
    String store(UUID tenantId, UUID messageId, String filename, String contentType, byte[] data);

    /**
     * Przechowuje bajty pending załącznika agenta w S3.
     *
     * @param tenantId    UUID tenanta
     * @param pendingId   UUID identyfikujący pending upload
     * @param filename    nazwa pliku
     * @param contentType MIME type
     * @param data        bajty załącznika
     * @return klucz S3 pod którym zapisano plik
     */
    String storePending(UUID tenantId, UUID pendingId, String filename, String contentType, byte[] data);

    /**
     * Generuje presigned URL do pobrania załącznika (TTL: 1 godzina).
     *
     * @param s3Key klucz S3
     * @return presigned URL
     */
    String presignedDownloadUrl(String s3Key);

    /**
     * Pobiera bajty załącznika z S3 (do wysyłki SMTP).
     *
     * @param s3Key klucz S3
     * @return bajty pliku
     */
    byte[] download(String s3Key);

    /**
     * Usuwa obiekt z S3 (retencja EPIC-30, BE-125).
     *
     * <p><strong>Idempotentne:</strong> brak obiektu pod podanym kluczem to sukces — S3/MinIO
     * odpowiada na {@code DeleteObject} nieistniejącego klucza kodem 204 (bez błędu), więc ponowny
     * przebieg purge po częściowej awarii nie zgłasza fałszywych niepowodzeń.
     *
     * <p><strong>Dlaczego własna metoda, a nie {@code RecordingService#deleteFromS3}:</strong>
     * tamta metoda łapie {@code S3Exception} i tylko loguje — wołający nie dowiaduje się o porażce.
     * Kolejność „S3 przed wierszem" (usuwaj wiersz wiadomości wyłącznie po skutecznym usunięciu
     * obiektu) wymaga jednoznacznego sygnału, dlatego każdy błąd S3 (usługi ORAZ klienta: sieć,
     * timeout) jest tu zgłaszany jako {@link EmailAttachmentException}.
     *
     * <p>Metoda NIE weryfikuje przynależności klucza do tenanta — to odpowiedzialność wołającego
     * (allow-lista prefiksu {@code email-attachments/{tenantId}/}, zob. {@code EmailAttachmentKeys}).
     *
     * @param s3Key klucz S3 obiektu (niepusty)
     * @throws IllegalArgumentException gdy klucz jest {@code null} lub pusty
     * @throws EmailAttachmentException gdy S3 zgłosi błąd
     */
    void delete(String s3Key);

    // =========================================================================
    // Listowanie pending (BE-131)
    // =========================================================================

    /**
     * Obiekt S3 pod prefiksem {@code pending/} zwrócony przez {@link #listPendingObjects} —
     * klucz i czas ostatniej modyfikacji (S3 {@code LastModified}), potrzebne do porównania
     * z TTL sweepu porzuconych załączników (retencja EPIC-30, BE-131).
     *
     * @param s3Key        klucz S3 obiektu
     * @param lastModified czas ostatniej modyfikacji zwrócony przez {@code ListObjectsV2}
     */
    record PendingObject(String s3Key, Instant lastModified) {}

    /**
     * Listuje WSZYSTKIE obiekty S3 pod prefiksem {@code email-attachments/{tenantId}/pending/}
     * danego tenanta — sweep porzuconych załączników (retencja EPIC-30, BE-131). Stronicuje
     * przez {@code ListObjectsV2} (do 1000 kluczy na stronę AWS SDK), zwraca pełną listę.
     *
     * <p>NIE filtruje po TTL ani po referencjach w {@code email_message.attachments[*].s3_key} —
     * to odpowiedzialność wołającego ({@code PendingAttachmentSweepJob}). Prefiks jest budowany
     * TĄ SAMĄ metodą co zapis ({@code EmailAttachmentKeys#pendingKey}), więc zwrócone klucze nie
     * mogą wyjść poza katalog {@code pending/} tego tenanta — wołający i tak weryfikuje to
     * dodatkowo przez {@code EmailAttachmentKeys#isOwnedByTenant} przed usunięciem (BE125-04/BE-143).
     *
     * @param tenantId UUID tenanta
     * @return lista obiektów pending (nigdy {@code null}), pusta gdy tenant nie ma żadnych
     * @throws EmailAttachmentException gdy S3 zgłosi błąd
     */
    List<PendingObject> listPendingObjects(UUID tenantId);
}
