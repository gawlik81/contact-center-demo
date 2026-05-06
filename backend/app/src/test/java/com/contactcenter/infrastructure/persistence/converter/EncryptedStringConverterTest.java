package com.contactcenter.infrastructure.persistence.converter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EncryptedStringConverter – AES-256-GCM konwerter JPA")
class EncryptedStringConverterTest {

    private EncryptedStringConverter converter;

    @BeforeEach
    void setUp() {
        // 32 bajty zerowe zakodowane w Base64 – poprawny klucz AES-256 dla testów
        String testKey = Base64.getEncoder().encodeToString(new byte[32]);
        converter = new EncryptedStringConverter(testKey);
    }

    @Nested
    @DisplayName("Roundtrip: szyfrowanie → deszyfrowanie")
    class RoundtripTests {

        @Test
        @DisplayName("roundtrip zwraca oryginalną wartość dla typowego SID Twilio")
        void roundtrip_twilioAccountSid_returnsOriginal() {
            String plain = "ACfb35df52c3b2af00f77f8b95b43dd77";
            String encrypted = converter.convertToDatabaseColumn(plain);
            String decrypted = converter.convertToEntityAttribute(encrypted);
            assertThat(decrypted).isEqualTo(plain);
        }

        @Test
        @DisplayName("roundtrip zwraca oryginalną wartość dla auth tokenu")
        void roundtrip_authToken_returnsOriginal() {
            String plain = "super-secret-auth-token-1234567890abcdef";
            String encrypted = converter.convertToDatabaseColumn(plain);
            String decrypted = converter.convertToEntityAttribute(encrypted);
            assertThat(decrypted).isEqualTo(plain);
        }

        @Test
        @DisplayName("roundtrip działa dla wartości jednoznakowej")
        void roundtrip_singleChar_returnsOriginal() {
            String plain = "x";
            String encrypted = converter.convertToDatabaseColumn(plain);
            String decrypted = converter.convertToEntityAttribute(encrypted);
            assertThat(decrypted).isEqualTo(plain);
        }

        @Test
        @DisplayName("zaszyfrowana wartość jest Base64 i dłuższa niż plaintext")
        void encrypted_isBase64AndLongerThanPlaintext() {
            String plain = "ACtest";
            String encrypted = converter.convertToDatabaseColumn(plain);
            // Powinno być poprawny Base64
            assertThat(Base64.getDecoder().decode(encrypted)).isNotNull();
            // Zaszyfrowany jest dłuższy (IV + tag + ciphertext)
            assertThat(encrypted.length()).isGreaterThan(plain.length());
        }
    }

    @Nested
    @DisplayName("Null safety – obsługa wartości null")
    class NullSafetyTests {

        @Test
        @DisplayName("convertToDatabaseColumn(null) zwraca null")
        void convertToDatabaseColumn_null_returnsNull() {
            assertThat(converter.convertToDatabaseColumn(null)).isNull();
        }

        @Test
        @DisplayName("convertToEntityAttribute(null) zwraca null")
        void convertToEntityAttribute_null_returnsNull() {
            assertThat(converter.convertToEntityAttribute(null)).isNull();
        }
    }

    @Nested
    @DisplayName("Losowość IV – różne ciphertexty dla tej samej wartości")
    class IvRandomnessTests {

        @Test
        @DisplayName("10 szyfrowania tej samej wartości daje 10 różnych cipherextów")
        void sameInput_multipleEncryptions_producesDifferentCiphertexts() {
            String plain = "ACfb35df52c3b2af00f77f8b95b43dd77";
            Set<String> ciphertexts = new HashSet<>();
            for (int i = 0; i < 10; i++) {
                ciphertexts.add(converter.convertToDatabaseColumn(plain));
            }
            assertThat(ciphertexts).hasSize(10);
        }
    }

    @Nested
    @DisplayName("Walidacja klucza")
    class KeyValidationTests {

        @Test
        @DisplayName("klucz krótszy niż 32 bajty rzuca IllegalArgumentException")
        void shortKey_throwsIllegalArgumentException() {
            String shortKey = Base64.getEncoder().encodeToString(new byte[16]); // 16 bajtów
            assertThatThrownBy(() -> new EncryptedStringConverter(shortKey))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("32");
        }
    }
}
