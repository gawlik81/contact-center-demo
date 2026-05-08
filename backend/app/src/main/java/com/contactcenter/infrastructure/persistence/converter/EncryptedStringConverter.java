package com.contactcenter.infrastructure.persistence.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Component
@Converter(autoApply = false)
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public EncryptedStringConverter(
            @Value("${app.encryption.secret}") String base64Secret) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Secret);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException(
                    "app.encryption.secret musi mieć min 32 bajty po dekodowaniu Base64 (current: " + keyBytes.length + ")");
        }
        this.secretKey = new SecretKeySpec(keyBytes, 0, 32, "AES");
        log.info("[EncryptedStringConverter] Konwerter AES-256-GCM zainicjalizowany");
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

            byte[] ciphertext = cipher.doFinal(attribute.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_LENGTH_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH_BYTES);
            System.arraycopy(ciphertext, 0, combined, IV_LENGTH_BYTES, ciphertext.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("[EncryptedStringConverter] Błąd szyfrowania: {}", e.getMessage());
            throw new IllegalStateException("Szyfrowanie wartości nie powiodło się", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(dbData);
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
            log.error("[EncryptedStringConverter] Błąd deszyfrowania: {}", e.getMessage());
            throw new IllegalStateException("Deszyfrowanie wartości nie powiodło się", e);
        }
    }
}
