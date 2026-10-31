package com.contactcenter.domain.social;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test jednostkowy dla {@link SocialIntegrationDecrypted#toString()} (naprawa security, code
 * review 2026-08-29): domyślny {@code toString()} generowany przez Javę dla recordów wypisałby
 * {@code accessToken} w plaintext, gdyby obiekt trafił kiedykolwiek bezpośrednio do loga
 * (np. nieostrożne {@code log.debug(decrypted)} w przyszłości, albo opakowanie w komunikat
 * wyjątku). Nadpisany {@code toString()} jest zabezpieczeniem defense-in-depth.
 */
@DisplayName("SocialIntegrationDecrypted.toString()")
class SocialIntegrationDecryptedTest {

    @Test
    @DisplayName("toString() maskuje accessToken i nie wypisuje go w plaintext")
    void toString_masksAccessToken() {
        String secretToken = "EAAB-super-secret-permanent-token";
        SocialIntegrationDecrypted decrypted = new SocialIntegrationDecrypted(
                UUID.randomUUID(), SocialPlatform.WHATSAPP, "1234567890", secretToken);

        String result = decrypted.toString();

        assertThat(result).doesNotContain(secretToken);
        assertThat(result).contains("REDACTED");
        // Pola nie-wrażliwe pozostają widoczne dla czytelności logów/debugowania
        assertThat(result).contains("WHATSAPP");
        assertThat(result).contains("1234567890");
    }
}
