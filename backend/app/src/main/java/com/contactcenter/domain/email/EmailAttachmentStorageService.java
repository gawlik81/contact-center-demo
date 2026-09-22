package com.contactcenter.domain.email;

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
}
