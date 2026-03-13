package com.contactcenter.security;

import dev.samstevens.totp.code.*;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Testy jednostkowe dla {@link MfaService}.
 *
 * <p>Testy weryfikują:
 * <ul>
 *   <li>Generowanie TOTP secret (długość, unikalność, format Base32)</li>
 *   <li>Generowanie QR code URI (obecność kluczowych elementów)</li>
 *   <li>Weryfikację poprawnego kodu TOTP (test z rzeczywistym generatorem)</li>
 *   <li>Odrzucanie nieprawidłowych kodów</li>
 *   <li>Obsługę edge cases (null, blank, zły format)</li>
 * </ul>
 */
@DisplayName("MfaService – TOTP RFC 6238")
class MfaServiceTest {

    /** MfaService z rzeczywistą implementacją (nie mock) do testów integracyjnych TOTP. */
    private MfaService mfaService;

    @BeforeEach
    void setUp() {
        mfaService = new MfaService();
    }

    // =========================================================================
    // generateSecret()
    // =========================================================================

    @Nested
    @DisplayName("generateSecret()")
    class GenerateSecret {

        @Test
        @DisplayName("Secret nie jest pusty")
        void shouldNotBeBlank() {
            String secret = mfaService.generateSecret();
            assertThat(secret).isNotBlank();
        }

        @Test
        @DisplayName("Secret ma długość 32 znaków (Base32 z 20 bajtów)")
        void shouldBe32CharactersLong() {
            String secret = mfaService.generateSecret();
            // 20 bajtów * 8 bit / 5 bit (Base32) = 32 znaki (bez padding)
            assertThat(secret).hasSize(32);
        }

        @Test
        @DisplayName("Secret składa się tylko ze znaków Base32 (A-Z, 2-7)")
        void shouldBeBase32Encoded() {
            String secret = mfaService.generateSecret();
            // RFC 4648 Base32: wielkie litery A-Z i cyfry 2-7
            assertThat(secret).matches("[A-Z2-7]{32}");
        }

        @Test
        @DisplayName("Dwa secrete są różne")
        void shouldBeUnique() {
            String s1 = mfaService.generateSecret();
            String s2 = mfaService.generateSecret();
            assertThat(s1).isNotEqualTo(s2);
        }

        @ParameterizedTest(name = "Iteracja {index}")
        @ValueSource(ints = {1, 2, 3, 4, 5})
        @DisplayName("5 wygenerowanych secretów jest unikatowych")
        void shouldBeUniqueAcrossMultipleCalls(int ignored) {
            var secrets = java.util.stream.IntStream.range(0, 5)
                    .mapToObj(i -> mfaService.generateSecret())
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(secrets).hasSize(5);
        }
    }

    // =========================================================================
    // generateQrCodeDataUri()
    // =========================================================================

    @Nested
    @DisplayName("generateQrCodeDataUri()")
    class GenerateQrCode {

        @Test
        @DisplayName("QR code URI zaczyna się od 'data:image/png;base64,'")
        void shouldReturnDataUri() {
            String secret = mfaService.generateSecret();

            String uri = mfaService.generateQrCodeDataUri(secret, "user@example.com");

            assertThat(uri).startsWith("data:image/png;base64,");
        }

        @Test
        @DisplayName("QR code URI jest niepusty i ma rozsądną długość")
        void shouldReturnNonEmptyUri() {
            String secret = mfaService.generateSecret();

            String uri = mfaService.generateQrCodeDataUri(secret, "user@example.com");

            // Minimalna długość data URI z małym obrazem PNG
            assertThat(uri.length()).isGreaterThan(100);
        }

        @Test
        @DisplayName("Różne emaile generują różne QR code URI")
        void shouldGenerateDifferentUriForDifferentEmails() {
            String secret = mfaService.generateSecret();

            String uri1 = mfaService.generateQrCodeDataUri(secret, "user1@test.com");
            String uri2 = mfaService.generateQrCodeDataUri(secret, "user2@test.com");

            assertThat(uri1).isNotEqualTo(uri2);
        }
    }

    // =========================================================================
    // verifyCode()
    // =========================================================================

    @Nested
    @DisplayName("verifyCode()")
    class VerifyCode {

        @Test
        @DisplayName("Poprawny kod TOTP z bieżącego kroku jest akceptowany")
        void shouldAcceptCurrentValidCode() throws Exception {
            String secret = mfaService.generateSecret();

            // Generujemy kod TOTP dla bieżącego czasu (to samo co zrobiłaby aplikacja MFA)
            String currentCode = generateCurrentCode(secret);

            assertThat(mfaService.verifyCode(secret, currentCode)).isTrue();
        }

        @Test
        @DisplayName("Całkowicie nieprawidłowy kod jest odrzucany")
        void shouldRejectInvalidCode() {
            String secret = mfaService.generateSecret();

            assertThat(mfaService.verifyCode(secret, "000000")).isFalse();
        }

        @Test
        @DisplayName("Kod z samych dziewiątek jest odrzucany")
        void shouldRejectAllNines() {
            String secret = mfaService.generateSecret();

            assertThat(mfaService.verifyCode(secret, "999999")).isFalse();
        }

        @Test
        @DisplayName("Null secret zwraca false")
        void shouldReturnFalseForNullSecret() {
            assertThat(mfaService.verifyCode(null, "123456")).isFalse();
        }

        @Test
        @DisplayName("Blank secret zwraca false")
        void shouldReturnFalseForBlankSecret() {
            assertThat(mfaService.verifyCode("", "123456")).isFalse();
        }

        @Test
        @DisplayName("Null kod zwraca false")
        void shouldReturnFalseForNullCode() {
            String secret = mfaService.generateSecret();
            assertThat(mfaService.verifyCode(secret, null)).isFalse();
        }

        @Test
        @DisplayName("Blank kod zwraca false")
        void shouldReturnFalseForBlankCode() {
            String secret = mfaService.generateSecret();
            assertThat(mfaService.verifyCode(secret, "")).isFalse();
        }

        @Test
        @DisplayName("Kod z spacjami jest sanityzowany przed weryfikacją")
        void shouldSanitizeCodeWithSpaces() throws Exception {
            String secret = mfaService.generateSecret();
            String currentCode = generateCurrentCode(secret);
            // Google Authenticator czasem wyświetla jako "123 456"
            String codeWithSpace = currentCode.substring(0, 3) + " " + currentCode.substring(3);

            assertThat(mfaService.verifyCode(secret, codeWithSpace)).isTrue();
        }

        @Test
        @DisplayName("Kod za długi (7 cyfr) jest odrzucany")
        void shouldRejectTooLongCode() {
            String secret = mfaService.generateSecret();
            // 7 cyfr – nie pasuje do kodu 6-cyfrowego
            assertThat(mfaService.verifyCode(secret, "1234567")).isFalse();
        }

        @Test
        @DisplayName("Błędny secret generuje false dla poprawnego kodu")
        void shouldRejectCodeWithWrongSecret() throws Exception {
            String secret1 = mfaService.generateSecret();
            String secret2 = mfaService.generateSecret();

            String codeForSecret1 = generateCurrentCode(secret1);

            // Kod wygenerowany dla secret1 nie powinien pasować do secret2
            assertThat(mfaService.verifyCode(secret2, codeForSecret1)).isFalse();
        }
    }

    // =========================================================================
    // MfaException
    // =========================================================================

    @Nested
    @DisplayName("generateQrCodeDataUri() – obsługa błędów")
    class QrCodeErrors {

        @Test
        @DisplayName("MfaException rzucana gdy generator QR zwraca błąd")
        void shouldThrowMfaExceptionOnQrError() {
            QrGenerator failingGenerator = mock(QrGenerator.class);

            try {
                when(failingGenerator.generate(any()))
                        .thenThrow(new dev.samstevens.totp.exceptions.QrGenerationException("Test error", null));
                when(failingGenerator.getImageMimeType()).thenReturn("image/png");
            } catch (dev.samstevens.totp.exceptions.QrGenerationException e) {
                // nie wystąpi przy konfiguracji mock
            }

            TimeProvider timeProvider = new SystemTimeProvider();
            CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
            CodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);

            MfaService serviceWithFailingQr = new MfaService(
                    new DefaultSecretGenerator(20),
                    verifier,
                    failingGenerator
            );

            assertThatThrownBy(() ->
                    serviceWithFailingQr.generateQrCodeDataUri("JBSWY3DPEHPK3PXP", "user@test.com")
            ).isInstanceOf(MfaService.MfaException.class)
             .hasMessageContaining("QR code");
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Generuje aktualny kod TOTP dla danego secretu (symulacja aplikacji MFA).
     * Używane tylko w testach.
     */
    private String generateCurrentCode(String secret) throws Exception {
        TimeProvider timeProvider = new SystemTimeProvider();
        CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
        long timeSlot = Math.floorDiv(timeProvider.getTime(), 30L);
        return codeGenerator.generate(secret, timeSlot);
    }
}
