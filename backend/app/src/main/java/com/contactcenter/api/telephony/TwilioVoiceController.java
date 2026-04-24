package com.contactcenter.api.telephony;

import com.contactcenter.infrastructure.config.TwilioProperties;
import com.contactcenter.security.TenantContext;
import com.twilio.jwt.accesstoken.AccessToken;
import com.twilio.jwt.accesstoken.VoiceGrant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Kontroler Twilio Voice SDK – generowanie Access Token i TwiML dla hold music.
 *
 * <p>Endpoint {@code GET /api/telephony/voice-token} zwraca Twilio Access Token
 * z {@link VoiceGrant}, który jest wymagany przez Twilio Voice JS SDK w przeglądarce
 * agenta do rejestracji urządzenia i odbioru połączeń przychodzących.
 *
 * <p>Endpoint {@code GET /api/telephony/hold-music} zwraca TwiML z komunikatem
 * oczekiwania – zastępuje zewnętrzny serwis twimlets.com jako {@code waitUrl}
 * w konferencji Twilio.
 */
@Slf4j
@RestController
@RequestMapping("/api/telephony")
@RequiredArgsConstructor
@Tag(name = "Twilio Voice SDK",
        description = "Generowanie Twilio Access Token dla Voice JS SDK oraz TwiML helper endpoints.")
public class TwilioVoiceController {

    /** TTL tokenu Voice SDK w sekundach (1 godzina). */
    private static final int VOICE_TOKEN_TTL_SECONDS = 3600;

    /** Prefix kluczy statystyk kolejek w Redis. Pełny klucz: {@code cache:queue:stats:{queueId}}. */
    private static final String QUEUE_STATS_KEY_PREFIX = "cache:queue:stats:";

    @Value("${app.hold-music-url:}")
    private String holdMusicUrl;

    private final TwilioProperties twilioProperties;
    private final RedisTemplate<String, Object> redisTemplate;

    // =========================================================================
    // Voice Token
    // =========================================================================

    /**
     * Generuje Twilio Access Token z {@link VoiceGrant} dla zalogowanego agenta.
     *
     * <p>Token jest wymagany przez Twilio Voice JS SDK do rejestracji urządzenia
     * ({@code Device.register()}) i odbioru połączeń przychodzących do identity
     * {@code agent-{userId}}.
     *
     * <p>Identity agenta to {@code agent-{userId}} – musi być zgodne z wartością
     * używaną przez {@code TwilioTelephonyAdapter.dialAgentIntoConference()},
     * który tworzy połączenie do {@code client:agent-{userId}}.
     *
     * @return JSON z polami {@code token} (JWT) i {@code identity} (agent identity)
     */
    @GetMapping("/features")
    @PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(summary = "Feature flags modułu telefonii")
    public ResponseEntity<Map<String, Object>> getFeatureFlags() {
        return ResponseEntity.ok(Map.of(
                "perTenantCallbackUrlEnabled", twilioProperties.isPerTenantCallbackUrlEnabled()
        ));
    }

    @GetMapping("/voice-token")
    @PreAuthorize("hasAnyRole('AGENT', 'SUPERVISOR', 'ADMIN')")
    @SecurityRequirement(name = "Bearer Authentication")
    @Operation(
            summary = "Generuj Twilio Access Token dla Voice JS SDK",
            description = """
                    Zwraca Twilio Access Token z VoiceGrant dla zalogowanego agenta.
                    Token jest wymagany przez Twilio Voice JS SDK w przeglądarce agenta
                    do rejestracji urządzenia i odbioru połączeń przychodzących.
                    Identity agenta: agent-{userId}.
                    TTL tokenu: 3600 sekund (1 godzina).
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Token wygenerowany pomyślnie"),
                    @ApiResponse(responseCode = "403", description = "Brak JWT lub niewystarczająca rola")
            }
    )
    public ResponseEntity<Map<String, String>> getVoiceToken() {
        UUID userId = TenantContext.getUserId();
        String identity = "agent-" + userId.toString();

        log.debug("[TwilioVoice] Generowanie Access Token dla identity={}", identity);

        VoiceGrant grant = new VoiceGrant();
        grant.setOutgoingApplicationSid(twilioProperties.getTwimlAppSid());
        grant.setIncomingAllow(true);

        AccessToken token = new AccessToken.Builder(
                twilioProperties.getAccountSid(),
                twilioProperties.getApiKeySid(),
                twilioProperties.getApiKeySecret().getBytes(StandardCharsets.UTF_8)
        )
                .identity(identity)
                .ttl(VOICE_TOKEN_TTL_SECONDS)
                .grant(grant)
                .build();

        String jwt = token.toJwt();
        log.info("[TwilioVoice] Access Token wygenerowany dla identity={}", identity);

        return ResponseEntity.ok(Map.of(
                "token", jwt,
                "identity", identity
        ));
    }

    // =========================================================================
    // Hold Music (waitUrl fallback)
    // =========================================================================

    /**
     * Zwraca TwiML z komunikatem oczekiwania i szacowanym czasem oczekiwania (EWT) – zastępuje zewnętrzny twimlets.com.
     *
     * <p>Używany jako {@code waitUrl} w TwiML {@code <Conference>} gdy klient czeka
     * na dołączenie agenta (moderatora). Endpoint jest <strong>publiczny</strong> –
     * Twilio wywołuje go bez JWT.
     *
     * <p>Gdy podany jest {@code queueId}, endpoint odczytuje EWT z Redis
     * ({@code cache:queue:stats:{queueId}}, pole {@code ewtSeconds}) i dołącza
     * stosowny komunikat TTS do odpowiedzi TwiML.
     *
     * @param queueId opcjonalny UUID kolejki – gdy podany, EWT jest odczytywany z Redis
     * @return TwiML XML z komunikatem {@code <Say>} i pauzą
     */
    @GetMapping(value = "/hold-music", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(
            summary = "TwiML muzyka oczekiwania (waitUrl dla konferencji)",
            description = """
                    Publiczny endpoint zwracający TwiML z komunikatem oczekiwania.
                    Używany przez Twilio jako waitUrl w Conference TwiML, gdy klient
                    czeka na połączenie z agentem. Zastępuje zewnętrzny twimlets.com.
                    Gdy podany jest queueId, dołącza komunikat EWT z Redis.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "TwiML XML z komunikatem oczekiwania")
            }
    )
    public ResponseEntity<String> holdMusic(
            @RequestParam(value = "queueId", required = false) UUID queueId,
            HttpServletRequest request) {
        log.debug("[TwilioVoice] Zwracam hold music TwiML, queueId={}", queueId);

        String ewtSayBlock = buildEwtSayBlock(queueId);
        String selfUrl = buildSelfUrl(request, queueId);
        String musicBlock = (holdMusicUrl != null && !holdMusicUrl.isBlank())
                ? "<Play>" + holdMusicUrl + "</Play>"
                : "<Pause length=\"25\"/>";

        String twiml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Response>"
                + "<Say language=\"pl-PL\">"
                + "Prosimy o chwile cierpliwosci. Laczymy z konsultantem."
                + "</Say>"
                + ewtSayBlock
                + musicBlock
                + "<Redirect method=\"GET\">" + selfUrl + "</Redirect>"
                + "</Response>";
        return ResponseEntity.ok(twiml);
    }

    /**
     * Buduje bezwzględny URL powrotny dla <Redirect> w TwiML hold-music.
     * Uwzględnia nagłówki X-Forwarded-Proto i Host ustawiane przez ngrok/reverse proxy.
     */
    private String buildSelfUrl(HttpServletRequest request, UUID queueId) {
        String proto = request.getHeader("X-Forwarded-Proto");
        if (proto == null || proto.isBlank()) {
            proto = request.getScheme();
        }
        String host = request.getHeader("Host");
        if (host == null || host.isBlank()) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        String url = proto + "://" + host + "/api/telephony/hold-music";
        if (queueId != null) {
            url += "?queueId=" + queueId;
        }
        return url;
    }

    /**
     * Buduje blok TwiML {@code <Say>} z szacowanym czasem oczekiwania (EWT).
     *
     * <p>Odczytuje wartość {@code ewtSeconds} z Redis dla podanego {@code queueId}.
     * Graceful degradation: gdy {@code queueId} jest null, Redis nie zwróci danych
     * lub wystąpi błąd – metoda zwraca pusty string (brak komunikatu EWT).
     *
     * @param queueId UUID kolejki lub null
     * @return fragment TwiML {@code <Say>} z komunikatem EWT lub pusty string
     */
    @SuppressWarnings("unchecked")
    private String buildEwtSayBlock(UUID queueId) {
        if (queueId == null) {
            return "";
        }
        try {
            String statsKey = QUEUE_STATS_KEY_PREFIX + queueId.toString();
            Object raw = redisTemplate.opsForValue().get(statsKey);
            if (raw == null) {
                log.debug("[TwilioVoice] Brak danych EWT w Redis dla queueId={}", queueId);
                return "";
            }
            Map<?, ?> stats = (Map<?, ?>) raw;
            Object ewtRaw = stats.get("ewtSeconds");
            if (ewtRaw == null) {
                return "";
            }
            int ewtSeconds = ((Number) ewtRaw).intValue();

            if (ewtSeconds < 0) {
                // Brak dostępnych agentów (WaitTimeEstimationService zapisuje -1 zamiast Integer.MAX_VALUE)
                log.debug("[TwilioVoice] EWT: brak dostępnych agentów dla queueId={}", queueId);
                return "<Say language=\"pl-PL\">"
                        + "Aktualnie brak dostepnych konsultantow. Prosimy o cierpliwosc."
                        + "</Say>";
            }

            if (ewtSeconds == 0) {
                return "";
            }

            String ewtMessage;
            if (ewtSeconds < 60) {
                ewtMessage = "Szacowany czas oczekiwania wynosi okolo " + ewtSeconds + " sekund.";
            } else {
                int minutes = (ewtSeconds + 30) / 60; // zaokrąglenie do najbliższej minuty
                ewtMessage = "Szacowany czas oczekiwania wynosi okolo " + minutes
                        + (minutes == 1 ? " minuty." : " minut.");
            }

            log.debug("[TwilioVoice] EWT dla queueId={}: {}s -> \"{}\"", queueId, ewtSeconds, ewtMessage);
            return "<Say language=\"pl-PL\">" + ewtMessage + "</Say>";

        } catch (Exception e) {
            log.warn("[TwilioVoice] Błąd odczytu EWT z Redis dla queueId={}: {}", queueId, e.getMessage());
            return "";
        }
    }
}
