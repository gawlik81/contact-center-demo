package com.contactcenter.api.plugin;

import com.contactcenter.api.plugin.dto.PluginValidationErrorResponse;
import com.contactcenter.domain.plugin.PluginStorageService;
import com.contactcenter.domain.plugin.PluginValidationService;
import com.contactcenter.domain.plugin.dto.PluginVersionDto;
import com.contactcenter.domain.plugin.dto.ValidationResult;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * REST API uploadu pluginów do globalnego katalogu (EPIC-28, BE-099).
 *
 * <p>Endpoint:
 * <ul>
 *   <li>POST /api/supervisor/plugins – upload nowego pluginu/wersji JAR-a</li>
 * </ul>
 *
 * <p>To jest upload do katalogu <strong>globalnego</strong> (nie instalacja per tenant –
 * to BE-100, kolejny ticket). Dostęp: wyłącznie ADMIN (refaktor ról – pluginy to jeden z 5 technicznych obszarów odebranych SUPERVISOR).
 *
 * <p>Pipeline: {@link PluginValidationService#validate} (ARCHITECTURE.md §11.4) → jeśli
 * {@code REJECTED}, JAR nie jest zapisywany nigdzie (storage/DB) i zwracamy 400 z listą
 * błędów; jeśli {@code VALIDATED}/{@code PENDING_REVIEW}, {@link PluginStorageService}
 * zapisuje JAR do S3/MinIO i wstawia wiersz {@code plugin_version}.
 */
@Slf4j
@RestController
@RequestMapping("/api/supervisor/plugins")
@RequiredArgsConstructor
@SecurityRequirement(name = "Bearer Authentication")
@Tag(name = "Plugins", description = "Upload i zarządzanie pluginami w globalnym katalogu (EPIC-28)")
public class PluginUploadController {

    /**
     * Guard rozmiaru na poziomie kontrolera, dodatkowo do guardu w
     * {@code PluginValidationService} (BE-098) — odrzuca oversized upload przed jakimkolwiek
     * przetwarzaniem (parsowanie ZIP, itp.), nie tylko przed zapisem.
     */
    private static final long MAX_JAR_SIZE_BYTES = 50L * 1024 * 1024;

    private final PluginValidationService pluginValidationService;
    private final PluginStorageService pluginStorageService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
        summary = "Upload pluginu (JAR) do globalnego katalogu",
        description = """
                Wgrywa JAR pluginu, waliduje go przez pełny pipeline bezpieczeństwa
                (ARCHITECTURE.md §11.4: rozmiar/MIME, checksum SHA-256, JSON Schema manifestu,
                statyczny skan bytecode ASM) i — jeśli walidacja przejdzie — zapisuje go do
                object storage oraz globalnego katalogu pluginów (tabela plugin/plugin_version).

                Plugin jest identyfikowany przez pluginKey z manifestu: jeśli plugin z tym
                kluczem już istnieje w katalogu, ta wersja jest dopisywana do niego; w przeciwnym
                razie tworzony jest nowy wpis w katalogu.

                Upload odrzucony (REJECTED) NIE jest zapisywany nigdzie — ani do object storage,
                ani do bazy danych.

                Limit rozmiaru: 50 MB. Wymagany plik JAR z META-INF/plugin-manifest.json.

                To jest upload do katalogu globalnego, NIE instalacja per tenant (BE-100).
                """,
        requestBody = @RequestBody(
            content = @Content(
                mediaType = MediaType.MULTIPART_FORM_DATA_VALUE,
                schema = @Schema(type = "string", format = "binary")
            )
        ),
        responses = {
            @ApiResponse(
                responseCode = "201",
                description = "Plugin zwalidowany i zapisany do katalogu",
                content = @Content(schema = @Schema(implementation = PluginVersionDto.class))
            ),
            @ApiResponse(
                responseCode = "400",
                description = "Walidacja JAR-a nie powiodła się (rozmiar, MIME, checksum, manifest, bytecode) "
                        + "— JAR nie został zapisany",
                content = @Content(schema = @Schema(implementation = PluginValidationErrorResponse.class))
            ),
            @ApiResponse(responseCode = "401", description = "Brak uwierzytelnienia"),
            @ApiResponse(responseCode = "403", description = "Brak uprawnień (wymagane ADMIN)")
        }
    )
    public ResponseEntity<?> uploadPlugin(
            @Parameter(description = "Plik JAR pluginu (max 50 MB)", required = true)
            @RequestPart("file") MultipartFile file
    ) throws IOException {

        UUID currentUserId = TenantContext.getUserId();

        if (file.isEmpty()) {
            log.warn("[PluginUpload] Pusty plik, uploadedBy={}", currentUserId);
            return ResponseEntity.badRequest()
                    .body(new PluginValidationErrorResponse(List.of("Plik JAR jest pusty")));
        }

        if (file.getSize() > MAX_JAR_SIZE_BYTES) {
            log.warn("[PluginUpload] Plik za duży: filename={}, size={}B, uploadedBy={}",
                    file.getOriginalFilename(), file.getSize(), currentUserId);
            return ResponseEntity.badRequest().body(new PluginValidationErrorResponse(
                    List.of("Plik JAR przekracza maksymalny dozwolony rozmiar 50MB")));
        }

        byte[] jarBytes = file.getBytes();

        log.info("[PluginUpload] POST /api/supervisor/plugins: filename={}, size={}B, uploadedBy={}",
                file.getOriginalFilename(), jarBytes.length, currentUserId);

        ValidationResult validationResult = pluginValidationService.validate(jarBytes, currentUserId);

        if (!validationResult.isValidated()) {
            log.warn("[PluginUpload] Upload odrzucony: filename={}, uploadedBy={}, errors={}",
                    file.getOriginalFilename(), currentUserId, validationResult.validationErrors());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new PluginValidationErrorResponse(validationResult.validationErrors()));
        }

        PluginVersionDto pluginVersionDto = pluginStorageService.storeValidatedJar(
                jarBytes, file.getOriginalFilename(), validationResult,
                TenantContext.getTenantId(),
                currentUserId);

        log.info("[PluginUpload] Plugin zapisany do katalogu: pluginKey={}, version={}, status={}, uploadedBy={}",
                pluginVersionDto.pluginKey(), pluginVersionDto.version(), pluginVersionDto.status(), currentUserId);

        return ResponseEntity.status(HttpStatus.CREATED).body(pluginVersionDto);
    }
}
