package com.contactcenter.domain.email;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Testy jednostkowe dla {@link EmailEncryptionService}.
 *
 * <p>Weryfikuje poprawność szyfrowania AES-256-GCM:
 * <ul>
 *   <li>Round-trip encrypt → decrypt zwraca oryginał</li>
 *   <li>Każde szyfrowanie generuje różny IV (różne szyfrogramy)</li>
 *   <li>Próba deszyfrowania z błędnym kluczem rzuca wyjątek</li>
 *   <li>Null i empty string są obsługiwane bez wyjątku</li>
 * </ul>
 */
@DisplayName("EmailEncryptionService – szyfrowanie AES-256-GCM")
class EmailEncryptionServiceTest {

    // Testowy klucz 256-bit (32 bajty = 64 znaki hex)
    private static final String TEST_KEY =
            "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

    private EmailEncryptionService service;

    @BeforeEach
    void setUp() {
        service = new EmailEncryptionService(TEST_KEY);
    }

    // =========================================================================
    // Round-trip
    // =========================================================================

    @Nested
    @DisplayName("encrypt / decrypt round-trip")
    class RoundTrip {

        @Test
        @DisplayName("encrypt + decrypt powinno zwrócić oryginalny tekst")
        void shouldReturnOriginalAfterRoundTrip() {
            String original = "moje-tajne-haslo-imap-2026!";

            String encrypted = service.encrypt(original);
            String decrypted = service.decrypt(encrypted);

            assertThat(decrypted).isEqualTo(original);
        }

        @Test
        @DisplayName("round-trip powinien działać dla hasła ze znakami specjalnymi")
        void shouldHandleSpecialCharacters() {
            String original = "P@$$w0rd!<>&\"'żółć";

            String encrypted = service.encrypt(original);
            String decrypted = service.decrypt(encrypted);

            assertThat(decrypted).isEqualTo(original);
        }

        @Test
        @DisplayName("round-trip powinien działać dla długiego hasła")
        void shouldHandleLongPassword() {
            String original = "a".repeat(256);

            String encrypted = service.encrypt(original);
            String decrypted = service.decrypt(encrypted);

            assertThat(decrypted).isEqualTo(original);
        }

        @Test
        @DisplayName("null powinien być zwracany bez zmian")
        void shouldReturnNullForNull() {
            assertThat(service.encrypt(null)).isNull();
            assertThat(service.decrypt(null)).isNull();
        }

        @Test
        @DisplayName("pusty string powinien być zwracany bez zmian")
        void shouldReturnEmptyForEmpty() {
            assertThat(service.encrypt("")).isEmpty();
            assertThat(service.decrypt("")).isEmpty();
        }
    }

    // =========================================================================
    // Losowość IV
    // =========================================================================

    @Nested
    @DisplayName("losowość IV")
    class IvRandomness {

        @Test
        @DisplayName("dwa szyfrowania tego samego tekstu powinny dać różne szyfrogramy")
        void shouldProduceDifferentCiphertextsForSamePlaintext() {
            String original = "to-samo-haslo";

            String encrypted1 = service.encrypt(original);
            String encrypted2 = service.encrypt(original);
            String encrypted3 = service.encrypt(original);

            // Każde szyfrowanie używa losowego IV – szyfrogramy muszą być różne
            assertThat(encrypted1).isNotEqualTo(encrypted2);
            assertThat(encrypted1).isNotEqualTo(encrypted3);
            assertThat(encrypted2).isNotEqualTo(encrypted3);
        }

        @Test
        @DisplayName("obie wersje zaszyfrowanego tekstu powinny dawać ten sam plaintext")
        void shouldDecryptBothCiphertextsToSamePlaintext() {
            String original = "haslo-do-testu";

            String encrypted1 = service.encrypt(original);
            String encrypted2 = service.encrypt(original);

            assertThat(service.decrypt(encrypted1)).isEqualTo(original);
            assertThat(service.decrypt(encrypted2)).isEqualTo(original);
        }
    }

    // =========================================================================
    // Błędy
    // =========================================================================

    @Nested
    @DisplayName("obsługa błędów")
    class ErrorHandling {

        @Test
        @DisplayName("deszyfrowanie zniekształconego Base64 powinno rzucić EmailEncryptionException")
        void shouldThrowOnCorruptedCiphertext() {
            assertThatThrownBy(() -> service.decrypt("to-nie-jest-poprawny-Base64!!!"))
                    .isInstanceOf(EmailEncryptionService.EmailEncryptionException.class);
        }

        @Test
        @DisplayName("deszyfrowanie zbyt krótkiego szyfrogramu powinno rzucić EmailEncryptionException")
        void shouldThrowOnTooShortCiphertext() {
            // Zakodowany Base64 string krótszy niż 12 bajtów IV
            String tooShort = java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});

            assertThatThrownBy(() -> service.decrypt(tooShort))
                    .isInstanceOf(EmailEncryptionService.EmailEncryptionException.class);
        }

        @Test
        @DisplayName("inicjalizacja z kluczem o złej długości powinna rzucić IllegalArgumentException")
        void shouldThrowOnInvalidKeyLength() {
            // 30 bajtów hex (60 znaków) zamiast 32 (64 znaki)
            String badKey = "deadbeef".repeat(7) + "dead"; // 30 bytes

            assertThatThrownBy(() -> new EmailEncryptionService(badKey))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("32 bajty");
        }

        @Test
        @DisplayName("deszyfrowanie tekstu zaszyfrowanego innym kluczem powinno rzucić wyjątek")
        void shouldThrowWhenDecryptingWithDifferentKey() {
            String differentKey =
                    "cafebabecafebabecafebabecafebabecafebabecafebabecafebabecafebabe";
            EmailEncryptionService otherService = new EmailEncryptionService(differentKey);

            String encrypted = service.encrypt("sekretne-haslo");

            assertThatThrownBy(() -> otherService.decrypt(encrypted))
                    .isInstanceOf(EmailEncryptionService.EmailEncryptionException.class);
        }
    }
}
