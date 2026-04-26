package com.contactcenter.domain.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

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
@Slf4j
@Service
public class EmailEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public EmailEncryptionService(
            @Value("${email.encryption-key:0000000000000000000000000000000000000000000000000000000000000000}")
            String hexKey) {

        byte[] keyBytes = hexToBytes(hexKey);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "email.encryption-key musi mieć 64 znaki hex (32 bajty dla AES-256). " +
                    "Ustaw zmienną ENV EMAIL_ENCRYPTION_KEY.");
        }
        if (isZeroKey(keyBytes)) {
            log.warn("[EmailEncryption][SECURITY] Klucz szyfrowania to same zera – NIGDY nie używaj w produkcji! Ustaw EMAIL_ENCRYPTION_KEY.");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        log.info("[EmailEncryption] Serwis szyfrowania AES-256-GCM zainicjalizowany");
    }

    // =========================================================================
    // Szyfrowanie
    // =========================================================================

    /**
     * Szyfruje tekst jawny do postaci Base64(IV + ciphertext + GCM-tag).
     *
     * @param plaintext tekst do zaszyfrowania (np. hasło IMAP/SMTP)
     * @return Base64-encoded zaszyfrowany tekst gotowy do zapisu w JSONB
     * @throws EmailEncryptionException gdy szyfrowanie się nie powiedzie
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Łączymy IV + ciphertext (ciphertext zawiera już GCM authentication tag)
            byte[] combined = new byte[IV_LENGTH_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH_BYTES);
            System.arraycopy(ciphertext, 0, combined, IV_LENGTH_BYTES, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);

        } catch (Exception e) {
            log.error("[EmailEncryption] Błąd szyfrowania: {}", e.getMessage());
            throw new EmailEncryptionException("Szyfrowanie hasła nie powiodło się", e);
        }
    }

    /**
     * Deszyfruje tekst z postaci Base64(IV + ciphertext + GCM-tag) do tekstu jawnego.
     *
     * @param ciphertext Base64-encoded zaszyfrowany tekst (z encrypt())
     * @return odszyfrowany tekst jawny
     * @throws EmailEncryptionException gdy deszyfrowanie się nie powiedzie
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            return ciphertext;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);

            if (combined.length < IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("Zbyt krótki szyfrogram – brak IV");
            }

            byte[] iv = new byte[IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);

            byte[] encryptedBytes = new byte[combined.length - IV_LENGTH_BYTES];
            System.arraycopy(combined, IV_LENGTH_BYTES, encryptedBytes, 0, encryptedBytes.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] plaintext = cipher.doFinal(encryptedBytes);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);

        } catch (Exception e) {
            log.error("[EmailEncryption] Błąd deszyfrowania: {}", e.getMessage());
            throw new EmailEncryptionException("Deszyfrowanie hasła nie powiodło się", e);
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private static boolean isZeroKey(byte[] key) {
        for (byte b : key) {
            if (b != 0) return false;
        }
        return true;
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Nieprawidłowy format hex: " + hex);
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }

    // =========================================================================
    // Wyjątek
    // =========================================================================

    /** Wyjątek szyfrowania/deszyfrowania haseł email. */
    public static class EmailEncryptionException extends RuntimeException {
        public EmailEncryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
