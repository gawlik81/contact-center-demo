package com.contactcenter.domain.social;

import java.util.UUID;

/**
 * Dane integracji social media z odszyfrowanym tokenem dostępu.
 *
 * <p>Używane wyłącznie przez adaptery w warstwie infrastruktury (np. {@code WhatsAppAdapter}),
 * które muszą wywołać zewnętrzne API platformy. Token jest odszyfrowywany na czas życia
 * tego obiektu i NIGDY nie powinien być logowany w plaintext.
 *
 * <p>Analogiczny wzorzec do {@code TenantTwilioConfigDecrypted} – adaptery nie mają
 * bezpośredniego dostępu do repozytorium ani serwisu szyfrowania (klasy pakietowe
 * w {@code domain.social}), tylko do tego DTO zwracanego przez publiczny
 * {@link SocialIntegrationService}.
 *
 * @param integrationId UUID integracji
 * @param platform      platforma social media
 * @param pageId        ID strony/konta na platformie (dla WhatsApp: phone_number_id)
 * @param accessToken   token dostępu w plaintext (odszyfrowany)
 */
public record SocialIntegrationDecrypted(
        UUID integrationId,
        SocialPlatform platform,
        String pageId,
        String accessToken
) {

    /**
     * Nadpisany {@code toString()} – domyślna implementacja generowana przez Javę dla recordów
     * wypisałaby {@code accessToken} w plaintext (defense-in-depth: żaden przyszły
     * {@code log.debug(decrypted)}/opakowanie w komunikat wyjątku nie ujawni tokenu).
     */
    @Override
    public String toString() {
        return "SocialIntegrationDecrypted[integrationId=" + integrationId
                + ", platform=" + platform
                + ", pageId=" + pageId
                + ", accessToken=***REDACTED***]";
    }
}
