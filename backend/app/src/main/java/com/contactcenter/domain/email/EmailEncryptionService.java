package com.contactcenter.domain.email;

/**
 * Serwis szyfrowania AES-256-GCM dla haseł IMAP/SMTP.
 *
 * <p>Algorytm: AES/GCM/NoPadding (256-bit).
 * Każde szyfrowanie generuje losowe 12-bajtowe IV (nonce).
 * IV jest dołączany na początku szyfrogramu: {@code [IV (12B)][ciphertext+tag (N+16B)]}.
 * Całość jest kodowana Base64.
 *
 * <p>Klucz szyfrowania pochodzi z właściwości {@code email.encryption-key}
 * (32-bajtowy hex, 64 znaki). W produkcji ustawiany przez ENV var {@code EMAIL_ENCRYPTION_KEY}.
 *
 * <p>Hasła NIGDY nie są logowane.
 */
public interface EmailEncryptionService {

    /**
     * Szyfruje tekst jawny do postaci Base64(IV + ciphertext + GCM-tag).
     *
     * @param plaintext tekst do zaszyfrowania (np. hasło IMAP/SMTP)
     * @return Base64-encoded zaszyfrowany tekst gotowy do zapisu w JSONB
     * @throws EmailEncryptionException gdy szyfrowanie się nie powiedzie
     */
    String encrypt(String plaintext);

    /**
     * Deszyfruje tekst z postaci Base64(IV + ciphertext + GCM-tag) do tekstu jawnego.
     *
     * @param ciphertext Base64-encoded zaszyfrowany tekst (z encrypt())
     * @return odszyfrowany tekst jawny
     * @throws EmailEncryptionException gdy deszyfrowanie się nie powiedzie
     */
    String decrypt(String ciphertext);

    /** Wyjątek szyfrowania/deszyfrowania haseł email. */
    class EmailEncryptionException extends RuntimeException {
        public EmailEncryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
