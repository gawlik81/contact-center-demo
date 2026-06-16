package com.contactcenter.api.email;

import com.contactcenter.api.email.dto.UploadedAttachmentResponse;
import com.contactcenter.domain.email.EmailAttachmentStorageService;
import com.contactcenter.security.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

/**
 * REST API dla operacji na załącznikach email.
 *
 * <p>Endpointy:
 * <ul>
 *   <li>POST /api/email/attachments/upload  – upload pliku przez agenta (pending)</li>
 *   <li>GET  /api/email/attachments/download – pobranie presigned URL lub redirect</li>
 * </ul>
 *
 * <p>Oba endpointy wymagają uwierzytelnienia JWT.
 * Nie są na liście {@code PUBLIC_PREFIXES} – TenantFilter weryfikuje JWT.
 */
@Slf4j
@RestController
@RequestMapping("/api/email/attachments")
@RequiredArgsConstructor
@Tag(name = "Email Attachments", description = "Upload i pobieranie załączników email")
public class EmailAttachmentController {

    /** Maksymalny rozmiar pliku: 25 MB (walidacja na poziomie aplikacji) */
    private static final long MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024;

    private final EmailAttachmentStorageService attachmentStorageService;

    // =========================================================================
    // Upload
    // =========================================================================

    /**
     * Upload pliku przez agenta przed wysłaniem odpowiedzi email.
     *
     * <p>Plik jest zapisywany w S3 pod kluczem {@code email-attachments/{tenantId}/pending/{uuid}/{filename}}.
     * Zwrócony {@code s3Key} należy przekazać w {@code EmailReplyRequest.attachments[].s3Key}
     * podczas wysyłania odpowiedzi.
     *
     * <p>Walidacja:
     * <ul>
     *   <li>Maksymalny rozmiar: 25 MB</li>
     *   <li>Typ pliku: dozwolony każdy – ostrzeżenie logowane dla podejrzanych typów</li>
     * </ul>
     *
     * @param file plik z FormData (multipart/form-data, pole "file")
     * @return metadane uploadu z kluczem S3
     */
    @PostMapping("/upload")
    @Operation(summary = "Upload załącznika email",
               description = "Wgrywa plik do S3 i zwraca s3Key do użycia przy wysyłaniu odpowiedzi email")
    public ResponseEntity<UploadedAttachmentResponse> uploadAttachment(
            @RequestParam("file") MultipartFile file) throws IOException {

        UUID tenantId = TenantContext.getTenantId();

        // Walidacja rozmiaru
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            log.warn("[EmailAttachment] Plik za duży: filename={}, size={}B, tenant={}",
                    file.getOriginalFilename(), file.getSize(), tenantId);
            return ResponseEntity.badRequest().build();
        }

        if (file.isEmpty()) {
            log.warn("[EmailAttachment] Pusty plik: tenant={}", tenantId);
            return ResponseEntity.badRequest().build();
        }

        String filename = sanitizeFilename(file.getOriginalFilename());
        String contentType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";

        // Ostrzeżenie dla podejrzanych typów (executable) – nie blokujemy, logujemy
        if (isSuspiciousContentType(contentType, filename)) {
            log.warn("[EmailAttachment] Podejrzany typ pliku: filename={}, contentType={}, tenant={}",
                    filename, contentType, tenantId);
        }

        UUID pendingId = UUID.randomUUID();
        byte[] data = file.getBytes();

        String s3Key = attachmentStorageService.storePending(tenantId, pendingId, filename, contentType, data);

        log.info("[EmailAttachment] Upload zakończony: pendingId={}, filename={}, size={}B, tenant={}",
                pendingId, filename, data.length, tenantId);

        return ResponseEntity.ok(new UploadedAttachmentResponse(
                pendingId,
                filename,
                contentType,
                data.length,
                s3Key
        ));
    }

    // =========================================================================
    // Download (presigned URL redirect)
    // =========================================================================

    /**
     * Pobiera presigned URL do pobrania załącznika z S3.
     *
     * <p>Zwraca HTTP 302 redirect na presigned URL (TTL: 1 godzina).
     *
     * <p>Ochrona przed IDOR: s3Key musi zaczynać się od {@code email-attachments/{tenantId}/}.
     * Agent nie może pobrać załącznika innego tenanta, nawet znając klucz S3.
     *
     * @param s3Key klucz S3 załącznika (zwrócony przez upload lub zawarty w EmailMessageResponse)
     * @return 302 redirect na presigned URL
     */
    @GetMapping("/download")
    @Operation(summary = "Pobierz załącznik email",
               description = "Zwraca redirect 302 na presigned URL S3 (TTL 1h). Wymaga uwierzytelnienia.")
    public ResponseEntity<Void> downloadAttachment(@RequestParam("s3Key") String s3Key) {
        UUID tenantId = TenantContext.getTenantId();

        // IDOR protection: s3Key musi należeć do bieżącego tenanta
        String expectedPrefix = "email-attachments/" + tenantId + "/";
        if (!s3Key.startsWith(expectedPrefix)) {
            log.warn("[EmailAttachment] Próba dostępu do nieautoryzowanego s3Key: key={}, tenant={}",
                    s3Key, tenantId);
            return ResponseEntity.status(403).build();
        }

        String presignedUrl = attachmentStorageService.presignedDownloadUrl(s3Key);

        log.info("[EmailAttachment] Przekierowanie do presigned URL: s3Key={}, tenant={}", s3Key, tenantId);

        return ResponseEntity.status(302)
                .location(URI.create(presignedUrl))
                .build();
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    /**
     * Sanityzuje nazwę pliku: usuwa ścieżki katalogów, zastępuje znaki specjalne.
     * Maksymalna długość nazwy pliku: 255 znaków.
     */
    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "attachment";
        }
        // Usuń path traversal
        String name = filename.replaceAll("[/\\\\]", "_");
        // Usuń znaki kontrolne i inne niebezpieczne
        name = name.replaceAll("[\\x00-\\x1f\\x7f]", "");
        // Ogranicz długość
        if (name.length() > 255) {
            name = name.substring(0, 255);
        }
        return name.isBlank() ? "attachment" : name;
    }

    /**
     * Sprawdza czy MIME type lub rozszerzenie pliku jest podejrzane (executable).
     * Nie blokuje – tylko loguje ostrzeżenie.
     */
    private boolean isSuspiciousContentType(String contentType, String filename) {
        if (contentType.contains("executable") || contentType.contains("x-msdownload")
                || contentType.contains("application/x-sh")) {
            return true;
        }
        String lower = filename.toLowerCase();
        return lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".sh")
                || lower.endsWith(".ps1") || lower.endsWith(".cmd") || lower.endsWith(".com");
    }
}
