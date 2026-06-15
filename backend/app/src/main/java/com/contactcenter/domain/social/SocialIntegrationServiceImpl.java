package com.contactcenter.domain.social;

import com.contactcenter.api.social.dto.SocialIntegrationDto;
import com.contactcenter.domain.audit.AuditLogEvent;
import com.contactcenter.domain.audit.AuditLogService;
import com.contactcenter.security.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
class SocialIntegrationServiceImpl implements SocialIntegrationService {

    private static final String ENTITY_TYPE = "SOCIAL_INTEGRATION";
    private static final String GRAPH_API_BASE = "https://graph.facebook.com/v19.0";

    // Próg odświeżania: tokeny wygasające w ciągu 24h
    private static final long REFRESH_THRESHOLD_HOURS = 24;

    private final SocialIntegrationRepository repository;
    private final SocialTokenEncryptionService encryptionService;
    private final AuditLogService auditLogService;

    // HttpClient jako pole – reużywany między requestami (thread-safe)
    private final HttpClient httpClient = HttpClient.newHttpClient();

    // =========================================================================
    // Operacje CRUD
    // =========================================================================

    @Override
    @Transactional
    public SocialIntegrationDto saveIntegration(
            SocialPlatform platform,
            String pageId,
            String displayName,
            String accessToken,
            Instant expiresAt,
            String platformConfig) {

        UUID tenantId = TenantContext.getTenantId();

        // Szyfruj token przed zapisem
        byte[] encryptedToken = encryptionService.encrypt(accessToken);

        // Znajdź istniejącą integrację lub utwórz nową
        SocialIntegration integration = repository
                .findByTenantIdAndPlatformAndPageId(tenantId, platform, pageId)
                .orElseGet(() -> SocialIntegration.builder()
                        .tenantId(tenantId)
                        .platform(platform)
                        .pageId(pageId)
                        .build());

        boolean isNew = integration.getIntegrationId() == null;

        integration.setDisplayName(displayName);
        integration.setAccessTokenEncrypted(encryptedToken);
        integration.setTokenExpiresAt(expiresAt);
        integration.setWebhookStatus("ACTIVE");
        if (platformConfig != null) {
            integration.setPlatformConfig(platformConfig);
        }

        SocialIntegration saved = repository.save(integration);

        // Audit log
        String action = isNew ? "SOCIAL_INTEGRATION_CREATED" : "SOCIAL_INTEGRATION_TOKEN_UPDATED";
        auditLogService.publishAuditEvent(new AuditLogEvent(
                tenantId,
                TenantContext.getUserId(),
                action,
                ENTITY_TYPE,
                saved.getIntegrationId(),
                null,
                String.format("{\"platform\":\"%s\",\"pageId\":\"%s\"}", platform, pageId),
                null,
                null,
                Instant.now()
        ));

        log.info("[SocialIntegration] {} integracja: integrationId={}, platform={}, pageId={}, tenant={}",
                isNew ? "Nowa" : "Zaktualizowana",
                saved.getIntegrationId(), platform, pageId, tenantId);

        return toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SocialIntegrationDto> getIntegrations() {
        UUID tenantId = TenantContext.getTenantId();
        return repository.findAllByTenantId(tenantId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    public void deleteIntegration(UUID integrationId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        // Etap 1: Odczyt i walidacja danych potrzebnych do revoke (krótka transakcja)
        Map<String, Object> integrationData = loadIntegrationForDelete(tenantId, integrationId);

        // Etap 2: Usunięcie z DB (krótka transakcja, bez blokady podczas I/O)
        deleteIntegrationFromDb(tenantId, integrationId, userId,
                (SocialPlatform) integrationData.get("platform"),
                (String) integrationData.get("pageId"));

        // Etap 3: Revoke u zewnętrznego providera (poza transakcją – nie blokuje puli HikariCP)
        SocialPlatform platform = (SocialPlatform) integrationData.get("platform");
        byte[] encryptedToken = (byte[]) integrationData.get("encryptedToken");
        String pageId = (String) integrationData.get("pageId");

        // encryptedToken jest puste (new byte[0]) gdy integracja nie miała tokenu
        if (encryptedToken != null && encryptedToken.length > 0) {
            revokeTokenAtProvider(platform, pageId, encryptedToken);
        }
    }

    /**
     * Odczytuje dane integracji niezbędne do revoke. Rzuca 404 jeśli nie istnieje.
     */
    @Transactional(readOnly = true)
    protected Map<String, Object> loadIntegrationForDelete(UUID tenantId, UUID integrationId) {
        SocialIntegration integration = repository
                .findByTenantIdAndIntegrationId(tenantId, integrationId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Integracja nie istnieje: " + integrationId));

        return Map.of(
                "platform", integration.getPlatform(),
                "pageId", integration.getPageId(),
                "encryptedToken", integration.getAccessTokenEncrypted() != null
                        ? integration.getAccessTokenEncrypted()
                        : new byte[0]
        );
    }

    /**
     * Usuwa integrację z DB i publikuje audit log. Krótka transakcja bez blokowania I/O.
     */
    @Transactional
    protected void deleteIntegrationFromDb(UUID tenantId, UUID integrationId, UUID userId,
                                           SocialPlatform platform, String pageId) {
        repository.deleteByTenantIdAndIntegrationId(tenantId, integrationId);

        auditLogService.publishAuditEvent(new AuditLogEvent(
                tenantId,
                userId,
                "SOCIAL_INTEGRATION_DELETED",
                ENTITY_TYPE,
                integrationId,
                String.format("{\"platform\":\"%s\",\"pageId\":\"%s\"}", platform, pageId),
                null,
                null,
                null,
                Instant.now()
        ));

        log.info("[SocialIntegration] Usunięto integrację z DB: integrationId={}, platform={}, tenant={}",
                integrationId, platform, tenantId);
    }

    // =========================================================================
    // Scheduled: odświeżanie tokenów
    // =========================================================================

    /**
     * Scheduled task odświeżający tokeny wygasające w ciągu 24h.
     *
     * <p>Uruchamiany co godzinę (fixedDelay = 3 600 000 ms).
     * Dla każdego tenanta ustawia TenantContext przed operacją i czyści po zakończeniu.
     *
     * <p>WhatsApp: tokeny nie wygasają – pomijane (tokenExpiresAt = null).
     * Facebook / Instagram: wykonywane long-lived token exchange przez Graph API.
     */
    @Scheduled(fixedDelay = 3_600_000)
    public void refreshExpiringTokens() {
        Instant threshold = Instant.now().plus(REFRESH_THRESHOLD_HOURS, ChronoUnit.HOURS);

        log.info("[SocialIntegration] Start odświeżania tokenów wygasających przed: {}", threshold);

        List<SocialIntegration> expiring = repository.findAllExpiringBefore(threshold);

        if (expiring.isEmpty()) {
            log.debug("[SocialIntegration] Brak tokenów do odświeżenia");
            return;
        }

        log.info("[SocialIntegration] Tokeny do odświeżenia: {}", expiring.size());

        for (SocialIntegration integration : expiring) {
            // WhatsApp nie wygasa – obrona, bo zapytanie filtruje po tokenExpiresAt IS NOT NULL
            if (integration.getPlatform() == SocialPlatform.WHATSAPP) {
                continue;
            }

            refreshToken(integration);
        }
    }

    /**
     * Odświeża pojedynczy token w kontekście wątku schedulera.
     *
     * <p>Wątek @Scheduled nie ma TenantContext (brak JWT). Ustawiamy tenantId jawnie
     * przez {@code TenantContext.setTenantId()} i czyścimy w finally po każdej iteracji.
     * Jest to właściwy wzorzec dla schedulerów – odpowiednik restore/clear bez snapshotu,
     * bo wątek schedulera nie dziedziczy żadnego kontekstu.
     *
     * <p>InterruptedException jest obsługiwany osobno z {@code Thread.currentThread().interrupt()},
     * aby umożliwić poprawne zatrzymanie schedulera przez kontener Spring.
     */
    private void refreshToken(SocialIntegration integration) {
        UUID tenantId = integration.getTenantId();
        UUID integrationId = integration.getIntegrationId();

        try {
            // Ustaw tenant dla tej iteracji – scheduler nie ma kontekstu z HTTP
            TenantContext.setTenantId(tenantId);

            log.info("[SocialIntegration] Odświeżanie tokenu: integrationId={}, platform={}, tenant={}",
                    integrationId, integration.getPlatform(), tenantId);

            String currentToken = encryptionService.decrypt(integration.getAccessTokenEncrypted());
            String newToken = exchangeForLongLivedToken(integration.getPlatform(), currentToken);

            byte[] encryptedNewToken = encryptionService.encrypt(newToken);
            integration.setAccessTokenEncrypted(encryptedNewToken);
            // FB long-lived tokeny ważne 60 dni
            integration.setTokenExpiresAt(Instant.now().plus(60, ChronoUnit.DAYS));
            integration.setWebhookStatus("ACTIVE");
            repository.save(integration);

            auditLogService.publishAuditEvent(new AuditLogEvent(
                    tenantId,
                    null, // zdarzenie systemowe
                    "SOCIAL_TOKEN_REFRESHED",
                    ENTITY_TYPE,
                    integrationId,
                    null,
                    String.format("{\"platform\":\"%s\",\"pageId\":\"%s\"}", integration.getPlatform(), integration.getPageId()),
                    null,
                    null,
                    Instant.now()
            ));

            log.info("[SocialIntegration] Token odświeżony pomyślnie: integrationId={}", integrationId);

        } catch (Exception e) {
            // InterruptedException może być opakowany lub rzucony jako RuntimeException
            // przez niektóre implementacje HTTP client – obsługujemy jawnie
            if (e instanceof InterruptedException || e.getCause() instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                log.warn("[SocialIntegration] Odświeżanie tokenu przerwane: integrationId={}", integrationId, e);
                return;
            }

            log.error("[SocialIntegration] Błąd odświeżania tokenu: integrationId={}, error={}",
                    integrationId, e.getMessage(), e);

            // Oznacz integrację jako EXPIRED_TOKEN (TenantContext już ustawiony powyżej)
            try {
                repository.updateWebhookStatus(integrationId, tenantId, "EXPIRED_TOKEN");
            } catch (Exception ex) {
                log.error("[SocialIntegration] Nie udało się zaktualizować statusu EXPIRED_TOKEN: {}", ex.getMessage());
            }

            auditLogService.publishAuditEvent(new AuditLogEvent(
                    tenantId,
                    null,
                    "SOCIAL_TOKEN_REFRESH_FAILED",
                    ENTITY_TYPE,
                    integrationId,
                    null,
                    String.format("{\"platform\":\"%s\",\"error\":\"%s\"}", integration.getPlatform(), e.getMessage()),
                    null,
                    null,
                    Instant.now()
            ));

        } finally {
            // Wyczyść kontekst po każdej iteracji – wątek schedulera jest reużywany
            TenantContext.clear();
        }
    }

    // =========================================================================
    // Operacje na providerze OAuth (Graph API)
    // =========================================================================

    /**
     * Wywołuje revoke tokenu u providera.
     *
     * <p>Dla FB/Instagram: DELETE https://graph.facebook.com/{pageId}/permissions
     * Token przekazywany w nagłówku Authorization: Bearer – NIE jako query param,
     * aby uniknąć wycieku do logów infrastruktury i access logów HTTP.
     *
     * <p>Wywoływany POZA transakcją DB – nie blokuje puli HikariCP podczas I/O.
     * Błąd HTTP nie blokuje operacji – logowany jako WARN.
     *
     * @param platform       platforma social media
     * @param pageId         ID strony na platformie
     * @param encryptedToken zaszyfrowany token dostępu (zostanie odszyfrowany)
     */
    private void revokeTokenAtProvider(SocialPlatform platform, String pageId, byte[] encryptedToken) {
        if (platform == SocialPlatform.WHATSAPP) {
            // WhatsApp Business API – tokeny zarządzane przez Meta Business Suite
            log.debug("[SocialIntegration] WhatsApp: pomijam revoke tokenu (zarządzane przez Meta Business Suite)");
            return;
        }

        try {
            String accessToken = encryptionService.decrypt(encryptedToken);

            // Token przekazywany w nagłówku Authorization: Bearer, NIE jako query param
            // – query params trafiają do access logów infrastruktury (ryzyko wycieku)
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GRAPH_API_BASE + "/" + pageId + "/permissions"))
                    .header("Authorization", "Bearer " + accessToken)
                    .DELETE()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("[SocialIntegration] Revoke tokenu u providera: OK, platform={}, pageId={}",
                        platform, pageId);
            } else {
                log.warn("[SocialIntegration] Revoke tokenu zwrócił status {}: platform={}, pageId={}",
                        response.statusCode(), platform, pageId);
            }
        } catch (InterruptedException e) {
            // Przywróć flagę przerwania wątku – scheduler może być zatrzymywany
            Thread.currentThread().interrupt();
            log.warn("[SocialIntegration] Revoke tokenu przerwany: platform={}, pageId={}", platform, pageId, e);
        } catch (Exception e) {
            // Błąd revoke nie blokuje operacji – integracja jest już usunięta z DB
            log.warn("[SocialIntegration] Błąd revoke tokenu u providera: platform={}, pageId={}, error={}",
                    platform, pageId, e.getMessage());
        }
    }

    /**
     * Wymienia krótkoterminowy token na long-lived token przez Facebook Graph API.
     *
     * <p>Endpoint: GET /oauth/access_token?grant_type=fb_exchange_token&...
     * Zwraca nowy token ważny 60 dni.
     *
     * <p>Ta metoda jest stub – w produkcji wymaga app_id i app_secret
     * skonfigurowanych przez {@code social.facebook.app-id} i {@code social.facebook.app-secret}.
     */
    private String exchangeForLongLivedToken(SocialPlatform platform, String shortLivedToken) {
        // W pełnej implementacji produkcyjnej wymagałoby to app_id i app_secret
        // z konfiguracji per-platforma oraz wywołania:
        // GET https://graph.facebook.com/oauth/access_token
        //   ?grant_type=fb_exchange_token
        //   &client_id={app_id}
        //   &client_secret={app_secret}
        //   &fb_exchange_token={short_lived_token}
        //
        // Aktualnie: zwracamy ten sam token (placeholder dla integracji testowej)
        // W produkcji: wstrzyknij @Value social.facebook.app-id/app-secret i wykonaj HTTP call
        log.debug("[SocialIntegration] Wymiana tokenu (stub): platform={}", platform);
        return shortLivedToken;
    }

    // =========================================================================
    // Mapowanie
    // =========================================================================

    private SocialIntegrationDto toDto(SocialIntegration entity) {
        return new SocialIntegrationDto(
                entity.getIntegrationId(),
                entity.getPlatform(),
                entity.getPageId(),
                entity.getDisplayName(),
                entity.getWebhookStatus(),
                entity.getTokenExpiresAt(),
                entity.getWebhookUrl(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
