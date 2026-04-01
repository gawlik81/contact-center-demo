package com.contactcenter.api.telephony;

import com.contactcenter.infrastructure.config.TwilioProperties;
import com.contactcenter.security.TenantContext;
import com.twilio.jwt.accesstoken.AccessToken;
import com.twilio.jwt.accesstoken.VoiceGrant;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    private final TwilioProperties twilioProperties;

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
     * Zwraca TwiML z komunikatem oczekiwania – zastępuje zewnętrzny twimlets.com.
     *
     * <p>Używany jako {@code waitUrl} w TwiML {@code <Conference>} gdy klient czeka
     * na dołączenie agenta (moderatora). Endpoint jest <strong>publiczny</strong> –
     * Twilio wywołuje go bez JWT.
     *
     * @return TwiML XML z komunikatem {@code <Say>} i pauzą
     */
    @GetMapping(value = "/hold-music", produces = MediaType.APPLICATION_XML_VALUE)
    @Operation(
            summary = "TwiML muzyka oczekiwania (waitUrl dla konferencji)",
            description = """
                    Publiczny endpoint zwracający TwiML z komunikatem oczekiwania.
                    Używany przez Twilio jako waitUrl w Conference TwiML, gdy klient
                    czeka na połączenie z agentem. Zastępuje zewnętrzny twimlets.com.
                    """,
            responses = {
                    @ApiResponse(responseCode = "200", description = "TwiML XML z komunikatem oczekiwania")
            }
    )
    public ResponseEntity<String> holdMusic() {
        log.debug("[TwilioVoice] Zwracam hold music TwiML");
        String twiml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Response>"
                + "<Say language=\"pl-PL\" loop=\"3\">"
                + "Prosimy o chwile cierpliwosci. Laczymy z konsultantem."
                + "</Say>"
                + "<Pause length=\"2\"/>"
                + "</Response>";
        return ResponseEntity.ok(twiml);
    }
}
