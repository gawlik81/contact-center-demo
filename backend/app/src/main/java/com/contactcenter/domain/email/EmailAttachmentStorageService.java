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
}
