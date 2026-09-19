package com.contactcenter.domain.social;

import com.contactcenter.api.social.dto.SocialIntegrationDto;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Serwis zarządzania integracjami social media (OAuth, tokeny, odświeżanie).
 *
 * <p>Odpowiada za:
 * <ul>
 *   <li>Zapis i aktualizację tokenów OAuth (zaszyfrowanych AES-256-GCM)</li>
 *   <li>Pobieranie listy integracji tenanta</li>
 *   <li>Revoke tokenu u providera i usunięcie z DB (DELETE)</li>
 *   <li>Automatyczne odświeżanie tokenów wygasających w ciągu 24h (@Scheduled)</li>
 *   <li>Logowanie operacji na tokenach do AuditLog</li>
 * </ul>
 *
 * <p>Token NIE JEST NIGDY logowany w plaintext.
 */
public interface SocialIntegrationService {

    /**
     * Zapisuje lub aktualizuje integrację OAuth dla danej platformy i strony.
     *
     * <p>Jeśli integracja (tenant, platform, pageId) już istnieje – aktualizuje token.
     * Jeśli nie – tworzy nową.
     *
     * @param platform      platforma social media
     * @param pageId        ID strony/konta na platformie
     * @param displayName   nazwa wyświetlana
     * @param accessToken   token dostępu w plaintext (zostanie zaszyfrowany)
     * @param expiresAt     czas wygaśnięcia tokenu (null dla WhatsApp)
     * @param platformConfig JSON string z dodatkową konfiguracją per-platforma
     * @return DTO zapisanej integracji (bez tokenu w plaintext)
     */
    SocialIntegrationDto saveIntegration(
            SocialPlatform platform,
            String pageId,
            String displayName,
            String accessToken,
            Instant expiresAt,
            String platformConfig);

    /**
     * Zwraca listę wszystkich integracji aktualnego tenanta.
     */
    List<SocialIntegrationDto> getIntegrations();

    /**
     * Revoke tokenu u providera OAuth i usuwa integrację z DB.
     *
     * <p>Podzielone na trzy etapy, aby uniknąć blokowania połączenia DB podczas
     * synchronicznego wywołania zewnętrznego Graph API (ryzyko wyczerpania puli HikariCP):
     * <ol>
     *   <li>Odczyt i walidacja integracji (krótka transakcja readOnly)</li>
     *   <li>Usunięcie z DB (krótka transakcja)</li>
     *   <li>Revoke u zewnętrznego providera (poza transakcją – błąd tylko loguje WARN)</li>
     * </ol>
     *
     * @param integrationId ID integracji do usunięcia
     * @throws ResponseStatusException 404 gdy integracja nie istnieje dla tego tenanta
     */
    void deleteIntegration(java.util.UUID integrationId);

    /**
     * Zwraca dane integracji z odszyfrowanym tokenem dostępu – do użytku przez adaptery
     * warstwy infrastruktury (np. {@code WhatsAppAdapter}) wywołujące zewnętrzne API platformy.
     *
     * <p>Repozytorium i serwis szyfrowania tokenów są klasami pakietowymi (package-private)
     * w {@code domain.social} – adaptery w {@code infrastructure.social} nie mają do nich
     * bezpośredniego dostępu i muszą korzystać z tej metody.
     *
     * @param integrationId UUID integracji
     * @return dane integracji z odszyfrowanym tokenem
     * @throws ResponseStatusException 404 gdy integracja nie istnieje dla aktualnego tenanta,
     *                                  422 gdy integracja nie ma skonfigurowanego tokenu
     */
    SocialIntegrationDecrypted getDecryptedIntegration(UUID integrationId);
}
