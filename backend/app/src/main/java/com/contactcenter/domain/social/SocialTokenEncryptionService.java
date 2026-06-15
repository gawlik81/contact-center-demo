package com.contactcenter.domain.social;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Serwis szyfrowania AES-256-GCM dla tokenów dostępu social media.
 *
 * <p>Algorytm: AES/GCM/NoPadding (256-bit).
 * Każde szyfrowanie generuje losowe 12-bajtowe IV (nonce).
 * Format szyfrogramu: {@code [IV (12B)][ciphertext + GCM-tag (N + 16B)]}.
 * Całość jest przechowywana jako {@code byte[]} w kolumnie BYTEA.
 *
 * <p>Klucz pochodzi z właściwości {@code social.token-encryption-key}
 * (32-bajtowy hex, 64 znaki). W produkcji ustawiany przez ENV var
 * {@code SOCIAL_TOKEN_ENCRYPTION_KEY}.
 *
 * <p>WAŻNE: tokeny NIGDY nie są logowane w plaintext.
 */
@Slf4j
@Service
class SocialTokenEncryptionService {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public SocialTokenEncryptionService(
            @Value("${social.token-encryption-key:default-dev-key-change-in-prod-32b!}")
            String rawKey) {

        byte[] keyBytes = deriveKeyBytes(rawKey);
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        log.info("[SocialTokenEncryption] Serwis szyfrowania AES-256-GCM zainicjalizowany");
    }

    // =========================================================================
    // API publiczne
    // =========================================================================

    /**
     * Szyfruje token dostępu do postaci {@code byte[]}.
     *
     * <p>Format wyniku: {@code [IV (12B)][ciphertext + GCM-tag]}.
     * Przeznaczony do zapisu w kolumnie BYTEA.
     *
     * @param plaintext token dostępu w plaintext
     * @return zaszyfrowany token gotowy do zapisu w DB
     * @throws SocialTokenEncryptionException gdy szyfrowanie nie powiedzie się
     */
    public byte[] encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("Token do zaszyfrowania nie może być null ani pusty");
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] result = new byte[IV_LENGTH_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, IV_LENGTH_BYTES);
            System.arraycopy(ciphertext, 0, result, IV_LENGTH_BYTES, ciphertext.length);

            log.debug("[SocialTokenEncryption] Token zaszyfrowany, długość szyfrogramu: {} bajtów", result.length);
            return result;

        } catch (Exception e) {
            log.error("[SocialTokenEncryption] Błąd szyfrowania tokenu: {}", e.getMessage());
            throw new SocialTokenEncryptionException("Szyfrowanie tokenu nie powiodło się", e);
        }
    }

    /**
     * Deszyfruje token z postaci {@code byte[]} (format BYTEA z DB).
     *
     * @param encrypted zaszyfrowany token z kolumny BYTEA
     * @return token w plaintext
     * @throws SocialTokenEncryptionException gdy deszyfrowanie nie powiedzie się
     */
    public String decrypt(byte[] encrypted) {
        if (encrypted == null || encrypted.length == 0) {
            throw new IllegalArgumentException("Zaszyfrowany token nie może być null ani pusty");
        }
        if (encrypted.length < IV_LENGTH_BYTES) {
            throw new SocialTokenEncryptionException("Zbyt krótki szyfrogram – brak IV", null);
        }
        try {
            byte[] iv = Arrays.copyOfRange(encrypted, 0, IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(encrypted, IV_LENGTH_BYTES, encrypted.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);

        } catch (Exception e) {
            log.error("[SocialTokenEncryption] Błąd deszyfrowania tokenu: {}", e.getMessage());
            throw new SocialTokenEncryptionException("Deszyfrowanie tokenu nie powiodło się", e);
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Wyprowadza 32-bajtowy klucz AES z konfiguracji.
     *
     * <p>Akceptuje dwa formaty:
     * <ul>
     *   <li>64-znakowy hex string (np. z ENV var) → dekodowany do 32 bajtów</li>
     *   <li>Dowolny string → SHA-256 hash jako klucz (wyłącznie DEV fallback)</li>
     * </ul>
     */
    private byte[] deriveKeyBytes(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            throw new IllegalArgumentException(
                    "social.token-encryption-key nie może być pusty. " +
                    "Ustaw ENV var SOCIAL_TOKEN_ENCRYPTION_KEY (64-znakowy hex).");
        }

        // Próba interpretacji jako hex
        if (rawKey.length() == 64 && rawKey.matches("[0-9a-fA-F]+")) {
            byte[] keyBytes = hexToBytes(rawKey);
            if (keyBytes.length == 32) {
                return keyBytes;
            }
        }

        // Fallback: hash SHA-256 z dowolnego stringa (tylko DEV!)
        try {
            log.warn("[SocialTokenEncryption] Klucz nie jest 64-znakowym hex – używam SHA-256 hash. " +
                    "W produkcji ustaw SOCIAL_TOKEN_ENCRYPTION_KEY jako 64-znakowy hex.");
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 niedostępny – nieprawidłowe środowisko JVM", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return bytes;
    }

    // =========================================================================
    // Wyjątek
    // =========================================================================

    /** Wyjątek szyfrowania/deszyfrowania tokenów social media. */
    public static class SocialTokenEncryptionException extends RuntimeException {
        public SocialTokenEncryptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
