package com.contactcenter.api.social;

import com.contactcenter.api.social.dto.OAuthInitiateResponse;
import com.contactcenter.api.social.dto.SocialIntegrationDto;
import com.contactcenter.api.social.dto.SocialIntegrationListResponse;
import com.contactcenter.api.social.dto.WhatsAppConnectRequest;
import com.contactcenter.domain.social.SocialPlatform;
import com.contactcenter.domain.social.SocialIntegrationService;
import com.contactcenter.infrastructure.social.WhatsAppGraphApiVerifier;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Kontroler REST obsługujący OAuth flow i zarządzanie integracjami social media.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>GET  /api/integrations              – lista integracji tenanta</li>
 *   <li>POST /api/integrations/{platform}/initiate – inicjacja OAuth (zwraca authorizationUrl)</li>
 *   <li>GET  /api/oauth/{platform}/callback – callback OAuth (publiczny, bez JWT)</li>
 *   <li>POST /api/integrations/WHATSAPP/connect – ręczne podłączenie WhatsApp Business (bez OAuth)</li>
 *   <li>DELETE /api/integrations/{integrationId} – revoke i usunięcie integracji</li>
 * </ul>
 *
 * <p>Endpoint callback (/api/oauth/{platform}/callback) jest PUBLICZNY –
 * wywoływany przez serwer OAuth Facebook/Instagram/WhatsApp bez JWT.
 * Zabezpieczenie przez weryfikację parametru {@code state} (OAuth CSRF protection):
 * state jest przechowywany w Redis z TTL 10 minut i usuwany po jednorazowym użyciu.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Social Media Integrations", description = "OAuth flow i zarządzanie tokenami platform social media")
public class SocialOAuthController {

    /** Prefix klucza Redis dla OAuth state (CSRF protection). */
    private static final String OAUTH_STATE_KEY_PREFIX = "oauth:state:";

    /** TTL klucza state w Redis – wystarczające na czas autoryzacji przez użytkownika. */
    private static final Duration OAUTH_STATE_TTL = Duration.ofMinutes(10);

    private final SocialIntegrationService integrationService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final WhatsAppGraphApiVerifier whatsAppGraphApiVerifier;

    @Value("${social.facebook.app-id:}")
    private String facebookAppId;

    @Value("${social.facebook.oauth-redirect-uri:http://localhost:8080/api/oauth/FACEBOOK/callback}")
    private String facebookRedirectUri;

    @Value("${social.instagram.app-id:}")
    private String instagramAppId;

    @Value("${social.instagram.oauth-redirect-uri:http://localhost:8080/api/oauth/INSTAGRAM/callback}")
    private String instagramRedirectUri;

    // =========================================================================
    // GET /api/integrations – lista integracji
    // =========================================================================

    @GetMapping("/api/integrations")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Lista integracji social media tenanta")
    public ResponseEntity<SocialIntegrationListResponse> listIntegrations() {
        var integrations = integrationService.getIntegrations();
        return ResponseEntity.ok(SocialIntegrationListResponse.of(integrations));
    }

    // =========================================================================
    // POST /api/integrations/{platform}/initiate – inicjacja OAuth
    // =========================================================================

    /**
     * Inicjuje OAuth flow – generuje URL autoryzacji z parametrem state.
     *
     * <p>State jest zapisywany w Redis z TTL 10 minut. Klucz:
     * {@code oauth:state:{state}} → wartość: tenantId.
     * W callbacku state jest weryfikowany i usuwany (single-use).
     */
    @PostMapping("/api/integrations/{platform}/initiate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Inicjacja OAuth – generuje URL autoryzacji")
    public ResponseEntity<OAuthInitiateResponse> initiateOAuth(
            @PathVariable @Parameter(description = "Platforma: FACEBOOK, INSTAGRAM, WHATSAPP") String platform) {

        SocialPlatform socialPlatform = parsePlatform(platform);
        UUID tenantId = TenantContext.getTenantId();

        // Generuj state i zapisz w Redis – powiązanie state → tenantId
        // Callback jest publiczny (bez JWT), więc tenantId musi być zawarty w Redis
        String state = UUID.randomUUID().toString();
        String stateKey = OAUTH_STATE_KEY_PREFIX + state;
        stringRedisTemplate.opsForValue().set(stateKey, tenantId.toString(), OAUTH_STATE_TTL);

        String authUrl = buildAuthorizationUrl(socialPlatform, state);

        log.info("[SocialOAuth] Inicjacja OAuth: platform={}, tenant={}", socialPlatform, tenantId);

        return ResponseEntity.ok(new OAuthInitiateResponse(socialPlatform, authUrl, state));
    }

    // =========================================================================
    // GET /api/oauth/{platform}/callback – callback OAuth (PUBLICZNY)
    // =========================================================================

    /**
     * Endpoint callback OAuth wywoływany przez serwer OAuth po autoryzacji użytkownika.
     *
     * <p>Endpoint jest PUBLICZNY (bez JWT) – Facebook/Instagram wywołują go bezpośrednio.
     * Bezpieczeństwo zapewnione przez:
     * <ul>
     *   <li>Weryfikację parametru {@code state} z Redis (OAuth CSRF protection, single-use)</li>
     *   <li>Wymianę kodu przez Graph API z app_secret (weryfikacja po stronie providera)</li>
     * </ul>
     *
     * <p>TenantContext jest ustawiany z Redis (tenantId zapisany przy initiateOAuth)
     * i czyszczony w bloku finally – endpoint jest stateless po zakończeniu żądania.
     *
     * <p>WhatsApp Business API nie używa standardowego OAuth 2.0 Code Flow –
     * obsługiwany przez oddzielny webhook flow. Dla platformy WHATSAPP
     * ten endpoint zwraca 400.
     *
     * @param platform platforma (FACEBOOK, INSTAGRAM, WHATSAPP)
     * @param code     kod autoryzacji od providera
     * @param state    parametr state (CSRF protection) – musi istnieć w Redis
     * @param error    opcjonalny błąd od providera (gdy użytkownik odmówił dostępu)
     */
    @GetMapping("/api/oauth/{platform}/callback")
    @Operation(summary = "Callback OAuth od providera (publiczny)")
    public ResponseEntity<SocialIntegrationDto> oauthCallback(
            @PathVariable String platform,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @RequestParam(name = "page_id", required = false) String pageId,
            @RequestParam(name = "display_name", required = false) String displayName) {

        SocialPlatform socialPlatform = parsePlatform(platform);

        // Obsługa odmowy przez użytkownika
        if (error != null) {
            log.warn("[SocialOAuth] Użytkownik odmówił dostępu: platform={}, error={}", socialPlatform, error);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Autoryzacja OAuth odrzucona przez użytkownika: " + error);
        }

        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Brakujący parametr 'code' w callbacku OAuth");
        }

        if (socialPlatform == SocialPlatform.WHATSAPP) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "WhatsApp Business API nie używa OAuth Code Flow. Użyj konfiguracji przez Meta Business Suite.");
        }

        // CRITICAL: Weryfikacja parametru state (CSRF protection)
        // state musi istnieć w Redis – zapisany przy initiateOAuth przez zalogowanego administratora
        if (state == null || state.isBlank()) {
            log.warn("[SocialOAuth] Callback bez parametru state – możliwy atak CSRF: platform={}", socialPlatform);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Brakujący parametr 'state' – nieprawidłowe żądanie OAuth");
        }

        String stateKey = OAUTH_STATE_KEY_PREFIX + state;
        String tenantIdStr = stringRedisTemplate.opsForValue().get(stateKey);

        if (tenantIdStr == null) {
            log.warn("[SocialOAuth] Nieznany lub wygasły state – możliwy atak CSRF: platform={}, state={}",
                    socialPlatform, state);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nieprawidłowy lub wygasły parametr 'state'. Spróbuj ponownie zainicjować połączenie.");
        }

        // Usuń state z Redis (single-use – zapobiega replay attack)
        stringRedisTemplate.delete(stateKey);

        UUID tenantId;
        try {
            tenantId = UUID.fromString(tenantIdStr);
        } catch (IllegalArgumentException e) {
            log.error("[SocialOAuth] Nieprawidłowy format tenantId w Redis: value={}", tenantIdStr);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Błąd wewnętrzny przy weryfikacji state OAuth");
        }

        log.info("[SocialOAuth] Callback OAuth: platform={}, tenant={}, code=[REDACTED]",
                socialPlatform, tenantId);

        // Ustaw TenantContext – wymagany przez serwis i RLS
        // Callback jest publiczny (bez JWT), tenantId pochodzi z Redis
        TenantContext.setTenantId(tenantId);
        try {
            // Wymieniamy code na token (stub – w produkcji wymaga wywołania Graph API)
            // W pełnej implementacji: POST https://graph.facebook.com/oauth/access_token
            //   ?client_id={app_id}&redirect_uri={redirect_uri}&client_secret={secret}&code={code}
            String accessToken = exchangeCodeForToken(socialPlatform, code);

            // Ustal pageId i displayName z odpowiedzi providera lub z parametrów
            String resolvedPageId = pageId != null ? pageId : extractPageIdFromToken(socialPlatform, accessToken);
            String resolvedDisplayName = displayName != null ? displayName : platform + " Page";

            // Tokeny FB/Instagram wygasają po 60 dniach (long-lived)
            Instant expiresAt = Instant.now().plusSeconds(60L * 24 * 3600);

            SocialIntegrationDto saved = integrationService.saveIntegration(
                    socialPlatform,
                    resolvedPageId,
                    resolvedDisplayName,
                    accessToken,
                    expiresAt,
                    null
            );

            log.info("[SocialOAuth] Integracja zapisana: integrationId={}, platform={}, pageId={}",
                    saved.integrationId(), socialPlatform, resolvedPageId);

            return ResponseEntity.ok(saved);

        } finally {
            // Zawsze czyść TenantContext – wątek HTTP jest reużywany z puli
            TenantContext.clear();
        }
    }

    // =========================================================================
    // POST /api/integrations/WHATSAPP/connect – ręczne podłączenie WhatsApp
    // =========================================================================

    /**
     * Ręcznie podłącza integrację WhatsApp Business (Cloud API).
     *
     * <p>WhatsApp Business API nie używa OAuth Code Flow (patrz {@link #buildAuthorizationUrl}) –
     * administrator generuje {@code phone_number_id} i permanentny access token w Meta Business
     * Suite / Meta for Developers i wkleja je bezpośrednio do formularza podłączenia.
     *
     * <p>Token nie wygasa (WhatsApp permanent access token), więc {@code expiresAt} przekazywane
     * do {@link SocialIntegrationService#saveIntegration} jest {@code null} – zgodnie z logiką
     * {@code refreshExpiringTokens()}, która jawnie pomija platformę WHATSAPP.
     *
     * <p>Jeśli tenant ma już podłączony WhatsApp pod tym samym {@code phoneNumberId}, integracja
     * zostanie zaktualizowana (nowy token, displayName) zamiast utworzenia duplikatu – obsługiwane
     * przez {@code findByTenantIdAndPlatformAndPageId} w warstwie serwisowej.
     *
     * <p><strong>Pre-flight weryfikacja (naprawa code review 2026-08-29):</strong> przed zapisem
     * integracja jest weryfikowana względem Meta Graph API przez
     * {@link WhatsAppGraphApiVerifier#verifyPhoneNumberAccess}. Wywołanie to musi wykonać się
     * PRZED {@link SocialIntegrationService#saveIntegration}, który jest {@code @Transactional} –
     * blokujące HTTP do zewnętrznego API nie może wykonywać się wewnątrz transakcji (dokładnie ten
     * anti-pattern naprawiony wcześniej w {@code WhatsAppAdapter}/{@code SocialMessageServiceImpl}).
     * Błędne dane (401/403/404 z Graph API) kończą się {@code IllegalArgumentException} → HTTP 422
     * (patrz {@code GlobalExceptionHandler.handleIllegalArgumentException}) – integracja NIE jest
     * zapisywana.
     */
    @PostMapping("/api/integrations/WHATSAPP/connect")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Ręczne podłączenie WhatsApp Business (Cloud API) – phone_number_id + permanentny access token")
    public ResponseEntity<SocialIntegrationDto> connectWhatsApp(
            @Valid @RequestBody WhatsAppConnectRequest request) {

        UUID tenantId = TenantContext.getTenantId();
        log.info("[SocialOAuth] Ręczne podłączenie WhatsApp: tenant={}, phoneNumberId={}",
                tenantId, request.phoneNumberId());

        // Pre-flight: weryfikacja (token, phoneNumberId) względem Graph API PRZED zapisem –
        // POZA jakąkolwiek transakcją (saveIntegration() poniżej jest @Transactional).
        whatsAppGraphApiVerifier.verifyPhoneNumberAccess(request.phoneNumberId(), request.accessToken());

        String platformConfig = buildWhatsAppPlatformConfig(request.businessAccountId());

        SocialIntegrationDto saved = integrationService.saveIntegration(
                SocialPlatform.WHATSAPP,
                request.phoneNumberId(),
                request.displayName(),
                request.accessToken(),
                null, // WhatsApp permanent access token nie wygasa
                platformConfig
        );

        log.info("[SocialOAuth] WhatsApp podłączony: integrationId={}, tenant={}, phoneNumberId={}",
                saved.integrationId(), tenantId, request.phoneNumberId());

        return ResponseEntity.ok(saved);
    }

    // =========================================================================
    // DELETE /api/integrations/{integrationId} – usuń integrację
    // =========================================================================

    @DeleteMapping("/api/integrations/{integrationId}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke tokenu u providera i usunięcie integracji z DB")
    public void deleteIntegration(
            @PathVariable @Parameter(description = "UUID integracji") UUID integrationId) {

        log.info("[SocialOAuth] Żądanie usunięcia integracji: integrationId={}", integrationId);
        integrationService.deleteIntegration(integrationId);
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private SocialPlatform parsePlatform(String platform) {
        try {
            return SocialPlatform.valueOf(platform.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Nieznana platforma: " + platform + ". Dozwolone: FACEBOOK, INSTAGRAM, WHATSAPP");
        }
    }

    /**
     * Buduje URL autoryzacji OAuth dla danej platformy.
     *
     * <p>Facebook / Instagram używają tego samego Graph API OAuth endpoint.
     * WhatsApp nie używa tego flow.
     */
    private String buildAuthorizationUrl(SocialPlatform platform, String state) {
        return switch (platform) {
            case FACEBOOK -> String.format(
                    "https://www.facebook.com/v19.0/dialog/oauth" +
                    "?client_id=%s" +
                    "&redirect_uri=%s" +
                    "&state=%s" +
                    "&scope=pages_messaging,pages_show_list,pages_read_engagement",
                    facebookAppId, facebookRedirectUri, state);

            case INSTAGRAM -> String.format(
                    "https://api.instagram.com/oauth/authorize" +
                    "?client_id=%s" +
                    "&redirect_uri=%s" +
                    "&scope=instagram_basic,instagram_manage_messages" +
                    "&response_type=code" +
                    "&state=%s",
                    instagramAppId, instagramRedirectUri, state);

            case WHATSAPP -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "WhatsApp Business API nie używa OAuth Code Flow. " +
                    "Skonfiguruj dostęp przez Meta Business Suite.");
        };
    }

    /**
     * Wymienia kod autoryzacji na token dostępu.
     *
     * <p>Stub implementacja – w produkcji wymaga wywołania Graph API:
     * POST https://graph.facebook.com/oauth/access_token
     *   ?client_id={app_id}&redirect_uri={uri}&client_secret={secret}&code={code}
     *
     * <p>Zwraca kod jako token (tylko dla środowiska DEV/test bez prawdziwych credentials).
     */
    private String exchangeCodeForToken(SocialPlatform platform, String code) {
        log.debug("[SocialOAuth] Wymiana code na token (stub): platform={}", platform);
        // TODO: implementacja produkcyjna – wywołanie Graph API z app_id + app_secret
        return code;
    }

    /**
     * Wyciąga page_id z tokenu przez wywołanie Graph API /me/accounts.
     *
     * <p>Stub – w produkcji: GET https://graph.facebook.com/me/accounts?access_token={token}
     */
    private String extractPageIdFromToken(SocialPlatform platform, String token) {
        log.debug("[SocialOAuth] Ekstrakcja page_id (stub): platform={}", platform);
        // TODO: implementacja produkcyjna – GET /me/accounts lub /me?fields=id
        return "page-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Buduje JSON string {@code platformConfig} dla integracji WhatsApp.
     *
     * <p>Zawiera {@code businessAccountId} (WABA ID) gdy podano – opcjonalne pole
     * przydatne np. do zarządzania szablonami wiadomości przez Graph API.
     * Budowane przez Jackson {@link ObjectMapper}/{@link ObjectNode}, nie przez ręczną
     * konkatenację stringów – unika ryzyka wygenerowania nieprawidłowego JSON-a
     * (np. gdy businessAccountId zawierałby znak cudzysłowu).
     *
     * @param businessAccountId opcjonalne WABA ID (może być null/puste)
     * @return JSON string lub {@code null} gdy businessAccountId nie podano
     */
    private String buildWhatsAppPlatformConfig(String businessAccountId) {
        if (businessAccountId == null || businessAccountId.isBlank()) {
            return null;
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.put("businessAccountId", businessAccountId);
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // Praktycznie nieosiągalne dla prostego ObjectNode z jednym polem tekstowym,
            // ale obsługujemy zgodnie z checked exception w sygnaturze Jacksona.
            log.warn("[SocialOAuth] Błąd serializacji platformConfig dla WhatsApp: {}", e.getMessage());
            return null;
        }
    }
}
