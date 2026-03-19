package com.contactcenter.api.recording;

import com.contactcenter.api.recording.dto.RecordingUrlResponse;
import com.contactcenter.domain.service.RecordingService;
import com.contactcenter.infrastructure.config.S3Properties;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * Kontroler REST dla nagrań rozmów telefonicznych (BE-010).
 *
 * <p>Dostępny dla roli SUPERVISOR lub ADMIN.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /api/recordings/{contactId} – generuje presigned URL ważny 1h</li>
 * </ul>
 *
 * <p><strong>Multi-tenant:</strong>
 * Dostęp jest ograniczony do nagrań własnego tenanta.
 * {@link TenantContext} jest ustawiany przez {@code TenantFilter} na podstawie JWT.
 *
 * <p><strong>Bezpieczeństwo:</strong>
 * Endpoint nie zwraca pliku bezpośrednio – generuje presigned URL.
 * URL wygasa po skonfigurowanym czasie (domyślnie 1h).
 * Każde wywołanie jest audytowane przez {@code @Audited} w {@link RecordingService}.
 */
@Slf4j
@RestController
@RequestMapping("/api/recordings")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPERVISOR') or hasRole('ADMIN')")
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Recordings", description = "Nagrania rozmów telefonicznych – presigned URL (SUPERVISOR/ADMIN)")
public class RecordingController {

    private final RecordingService recordingService;
    private final S3Properties s3Properties;

    /**
     * Generuje presigned URL do pobrania nagrania rozmowy.
     *
     * <p>URL jest ważny przez czas skonfigurowany w {@code s3.presigned-url-expiration-minutes}
     * (domyślnie 60 minut). Nie zwraca pliku bezpośrednio.
     *
     * <p>Dostęp jest ograniczony do nagrań własnego tenanta (enforced przez TenantContext + RLS).
     *
     * @param contactId UUID kontaktu, dla którego generowany jest presigned URL
     * @return HTTP 200 z presigned URL lub HTTP 404 jeśli nagranie nie istnieje
     */
    @GetMapping("/{contactId}")
    @Operation(
        summary = "Pobierz presigned URL nagrania",
        description = "Generuje jednorazowy presigned URL do pobrania nagrania rozmowy. " +
                      "URL wygasa po 1h (konfigurowalne). Dostępny dla SUPERVISOR i ADMIN. " +
                      "Dostęp ograniczony do nagrań własnego tenanta.",
        parameters = {
            @Parameter(
                name = "contactId",
                description = "UUID kontaktu (rozmowy), dla której pobieramy nagranie",
                required = true,
                example = "550e8400-e29b-41d4-a716-446655440000"
            )
        },
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Presigned URL wygenerowany pomyślnie",
                content = @Content(schema = @Schema(implementation = RecordingUrlResponse.class))
            ),
            @ApiResponse(responseCode = "404", description = "Nagranie nie istnieje dla podanego contactId"),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak roli SUPERVISOR lub ADMIN")
        }
    )
    public ResponseEntity<RecordingUrlResponse> getRecordingUrl(
            @PathVariable UUID contactId
    ) {
        UUID tenantId = TenantContext.getTenantId();

        log.debug("[Recording] Żądanie presigned URL: contactId={}, tenantId={}", contactId, tenantId);

        Optional<String> urlOpt = recordingService.generatePresignedUrl(contactId, tenantId);

        if (urlOpt.isEmpty()) {
            log.debug("[Recording] Nagranie nie znalezione: contactId={}", contactId);
            return ResponseEntity.notFound().build();
        }

        RecordingUrlResponse response = new RecordingUrlResponse(
                contactId,
                urlOpt.get(),
                java.time.Instant.now().plusSeconds(
                        s3Properties.getPresignedUrlExpirationMinutes() * 60
                )
        );

        return ResponseEntity.ok(response);
    }
}
