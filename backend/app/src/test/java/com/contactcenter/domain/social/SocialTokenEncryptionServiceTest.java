package com.contactcenter.domain.social;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Testy jednostkowe serwisu szyfrowania tokenów social media.
 *
 * <p>Testy weryfikują:
 * <ul>
 *   <li>Poprawność szyfrowania i deszyfrowania (round-trip)</li>
 *   <li>Unikalność IV – ten sam plaintext daje różne szyfrogramy</li>
 *   <li>Walidację danych wejściowych (null, puste)</li>
 *   <li>Odporność na zmodyfikowany szyfrogram (GCM authentication tag)</li>
 * </ul>
 */
@DisplayName("SocialTokenEncryptionService")
class SocialTokenEncryptionServiceTest {

    // 64-znakowy hex = 32 bajty AES-256
    private static final String TEST_KEY_HEX =
            "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20";

    private SocialTokenEncryptionService service;

    @BeforeEach
    void setUp() {
        service = new SocialTokenEncryptionService(TEST_KEY_HEX);
    }

    @Test
    @DisplayName("szyfruje i deszyfruje token poprawnie (round-trip)")
    void shouldEncryptAndDecryptToken() {
        String originalToken = "EAABsbCS...fakeAccessToken.1234567890";

        byte[] encrypted = service.encrypt(originalToken);
        String decrypted = service.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(originalToken);
    }

    @Test
    @DisplayName("każde szyfrowanie generuje unikalne IV (różne szyfrogramy)")
    void shouldGenerateUniqueIvForEachEncryption() {
        String token = "sameToken";

        byte[] encrypted1 = service.encrypt(token);
        byte[] encrypted2 = service.encrypt(token);

        assertThat(encrypted1).isNotEqualTo(encrypted2);
        // Ale deszyfrowanie obu daje ten sam wynik
        assertThat(service.decrypt(encrypted1)).isEqualTo(token);
        assertThat(service.decrypt(encrypted2)).isEqualTo(token);
    }

    @Test
    @DisplayName("szyfruje token zawierający znaki specjalne i Unicode")
    void shouldHandleSpecialCharactersInToken() {
        String token = "token_with_special=chars&unicode=złoto";

        byte[] encrypted = service.encrypt(token);
        String decrypted = service.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(token);
    }

    @Test
    @DisplayName("rzuca wyjątek przy szyfrowaniu null")
    void shouldThrowOnNullEncrypt() {
        assertThatThrownBy(() -> service.encrypt(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rzuca wyjątek przy szyfrowaniu pustego stringa")
    void shouldThrowOnEmptyEncrypt() {
        assertThatThrownBy(() -> service.encrypt(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rzuca wyjątek przy deszyfrowaniu null")
    void shouldThrowOnNullDecrypt() {
        assertThatThrownBy(() -> service.decrypt(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rzuca wyjątek przy deszyfrowaniu zmodyfikowanego szyfrogramu (GCM tag)")
    void shouldThrowOnTamperedCiphertext() {
        String token = "validToken";
        byte[] encrypted = service.encrypt(token);

        // Modyfikujemy ostatni bajt szyfrogramu (GCM authentication tag)
        encrypted[encrypted.length - 1] ^= 0xFF;

        assertThatThrownBy(() -> service.decrypt(encrypted))
                .isInstanceOf(SocialTokenEncryptionService.SocialTokenEncryptionException.class);
    }

    @Test
    @DisplayName("akceptuje klucz jako dowolny string (DEV fallback SHA-256)")
    void shouldAcceptStringKeyAsFallback() {
        SocialTokenEncryptionService devService =
                new SocialTokenEncryptionService("my-dev-key-string");

        String token = "testToken";
        byte[] encrypted = devService.encrypt(token);
        assertThat(devService.decrypt(encrypted)).isEqualTo(token);
    }

    @Test
    @DisplayName("szyfrogram zawiera przynajmniej 12 bajtów IV + dane")
    void shouldProduceCiphertextWithMinimumLength() {
        String token = "x";
        byte[] encrypted = service.encrypt(token);

        // IV = 12B + ciphertext + GCM-tag (16B) => minimum 29 bajtów dla 1-bajtowego plaintextu
        assertThat(encrypted.length).isGreaterThanOrEqualTo(12 + 1 + 16);
    }
}
