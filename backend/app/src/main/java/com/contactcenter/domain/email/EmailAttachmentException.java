package com.contactcenter.domain.email;

/**
 * Wyjątek operacji na załącznikach e-mail w S3 ({@link EmailAttachmentStorageService}).
 *
 * <p>Wydzielony z {@code EmailAttachmentStorageServiceImpl} do osobnej, publicznej klasy (BE-125):
 * {@link EmailAttachmentStorageService#delete(String)} jest częścią publicznego kontraktu, a jego
 * wołający spoza pakietu {@code domain.email} (retencja, RODO) muszą móc odróżnić awarię S3 od
 * błędu programistycznego.
 */
public class EmailAttachmentException extends RuntimeException {

    public EmailAttachmentException(String message, Throwable cause) {
        super(message, cause);
    }
}
