package com.contactcenter.domain.gdpr;

import com.contactcenter.api.customer.dto.AnonymizePreviewResponse;
import com.contactcenter.domain.audit.AuditLogEvent;
import com.contactcenter.domain.audit.AuditLogService;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.domain.email.EmailAttachmentKeys;
import com.contactcenter.domain.email.EmailAttachmentStorageService;
import com.contactcenter.domain.exception.ConflictException;
import com.contactcenter.domain.recording.RecordingService;
import com.contactcenter.infrastructure.config.S3Properties;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Implementacja {@link GdprService} (BE-031, rozszerzona BE-129).
 *
 * <p>Od BE-129: obie operacje wołają natywne funkcje PostgreSQL przez {@link GdprRepository}
 * ({@code anonymize_customer} — DB-062, {@code export_customer_data} — DB-061) zamiast budować
 * logikę RODO w Javie. Repozytorium zwraca surowy JSONB (jako tekst); ta klasa go interpretuje,
 * mapuje na wyjątki domenowe i — dla anonimizacji — sprząta obiekty S3 PO COMMIT.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class GdprServiceImpl implements GdprService {

    private final GdprRepository gdprRepository;
    private final CustomerService customerService;
    private final RecordingService recordingService;
    private final EmailAttachmentStorageService emailAttachmentStorageService;
    private final S3Properties s3Properties;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // Art. 15/20 – Eksport danych klienta
    // =========================================================================

    @Override
    public byte[] exportCustomerData(UUID customerId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        log.info("[GDPR] Export danych klienta: customerId={}, tenant={}, requestedBy={}",
                customerId, tenantId, userId);

        // Zachowuje dotychczasowy kontrakt 404 (wyklucza klientów is_deleted=TRUE) — spójne z
        // guardem wewnątrz export_customer_data (DB-061), który dla takich klientów też rzuca.
        customerService.findById(customerId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Klient nie istnieje lub nie należy do bieżącego tenanta: " + customerId));

        String json = gdprRepository.exportCustomerData(customerId, tenantId);
        JsonNode exportResult = parseJson(json);

        byte[] zipBytes = buildExportZip(customerId, tenantId, exportResult);

        // Audyt GDPR_EXPORT zapisuje wyłącznie Java — export_customer_data (po DB-061) nie pisze
        // już własnego wpisu (uniknięcie podwójnego audytu tej samej operacji).
        auditLogService.publishAuditEvent(new AuditLogEvent(
                tenantId,
                userId,
                "GDPR_EXPORT",
                "CUSTOMER",
                customerId,
                null,
                null,
                null,
                null,
                Instant.now()
        ));

        log.info("[GDPR] Export zakończony: customerId={}, zipSize={}B", customerId, zipBytes.length);
        return zipBytes;
    }

    // =========================================================================
    // Art. 17 – Anonimizacja klienta
    // =========================================================================

    @Override
    public void anonymizeCustomer(UUID customerId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        log.info("[GDPR] Anonimizacja klienta: customerId={}, tenant={}, requestedBy={}",
                customerId, tenantId, userId);

        JsonNode result = performAnonymize(customerId, tenantId, userId, false);

        // Kolejność DB → S3 (DESIGN R8): baza jest już zacommitowana (GdprRepository#anonymize
        // zwróciło się z własnej transakcji @Transactional) — sprzątanie S3 poniżej jest best-effort
        // i NIE może cofnąć anonimizacji PII w bazie, nawet gdy się nie powiedzie.
        List<String> s3Keys = jsonArrayToStrings(result.get("s3_keys"));
        List<String> failedKeys = cleanupS3Objects(tenantId, s3Keys);

        log.info("[GDPR] Klient zanonimizowany: customerId={}, tenant={}, s3KeysTotal={}, s3Failed={}",
                customerId, tenantId, s3Keys.size(), failedKeys.size());
    }

    @Override
    public AnonymizePreviewResponse previewAnonymizeCustomer(UUID customerId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();

        log.debug("[GDPR] Podgląd anonimizacji klienta: customerId={}, tenant={}", customerId, tenantId);

        // dryRun=true — guard rekordów w toku (DIALING/PROCESSING) NIE ma zastosowania: podgląd
        // niczego nie zmienia, więc nie ma czego chronić przed współbieżną mutacją.
        JsonNode result = performAnonymize(customerId, tenantId, userId, true);
        return toPreviewResponse(result);
    }

    // =========================================================================
    // Wspólna orkiestracja anonimizacji (rzeczywista i podgląd)
    // =========================================================================

    /**
     * Woła {@link GdprRepository#anonymize} i mapuje wynik na wyjątki domenowe.
     *
     * @param dryRun {@code false} = rzeczywista anonimizacja, {@code true} = wyłącznie podgląd
     * @return sparsowany JSONB wyniku funkcji SQL {@code anonymize_customer}
     * @throws EntityNotFoundException gdy klient nie istnieje w tenancie
     * @throws ConflictException gdy {@code dryRun = false} i klient ma rekord w toku
     */
    private JsonNode performAnonymize(UUID customerId, UUID tenantId, UUID userId, boolean dryRun) {
        GdprRepository.AnonymizeAttempt attempt = gdprRepository.anonymize(customerId, tenantId, userId, dryRun);

        if (!attempt.customerExists()) {
            throw new EntityNotFoundException(
                    "Klient nie istnieje lub nie należy do bieżącego tenanta: " + customerId);
        }
        if (attempt.rejectedInProgress()) {
            throw new ConflictException(
                    "Klient ma powiązany rekord w trakcie realizacji połączenia (kampania w statusie "
                    + "DIALING lub oddzwonienie w statusie PROCESSING) — anonimizacja odrzucona. "
                    + "Poczekaj na zakończenie połączenia i spróbuj ponownie: customerId=" + customerId);
        }
        return parseJson(attempt.resultJson());
    }

    private AnonymizePreviewResponse toPreviewResponse(JsonNode result) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        JsonNode countsNode = result.get("counts");
        if (countsNode != null) {
            countsNode.fields().forEachRemaining(e -> counts.put(e.getKey(), e.getValue().asInt()));
        }
        int s3ObjectsToDelete = result.has("s3_keys") ? result.get("s3_keys").size() : 0;

        return new AnonymizePreviewResponse(
                result.path("dry_run").asBoolean(true),
                counts,
                result.path("matched_by_link").asInt(0),
                result.path("matched_by_identifier").asInt(0),
                s3ObjectsToDelete
        );
    }

    // =========================================================================
    // Sprzątanie S3 po anonimizacji (best-effort, PO COMMIT)
    // =========================================================================

    /**
     * Usuwa obiekty S3 wskazane przez {@code anonymize_customer} (nagrania, EML, załączniki
     * e-mail/social — w tym {@code pending/} wiadomości OUTBOUND).
     *
     * <p>Każdy klucz jest klasyfikowany do jednej z dwóch allow-list ({@link EmailAttachmentKeys})
     * przed usunięciem — DWA różne schematy kluczy w tym samym buckecie, nie jedna allow-lista.
     * Niepowodzenia (błąd S3 albo klucz niepasujący do żadnej allow-listy) NIE przerywają pętli —
     * lista zebrana na końcu jest logowana na poziomie ERROR, żeby dało się dokończyć ręcznie.
     *
     * @return klucze, których NIE udało się usunąć (puste gdy wszystko się powiodło)
     */
    private List<String> cleanupS3Objects(UUID tenantId, List<String> s3Keys) {
        if (s3Keys.isEmpty()) {
            return List.of();
        }

        List<String> failed = new ArrayList<>();
        for (String key : s3Keys) {
            boolean isEmailAttachment = EmailAttachmentKeys.isOwnedByTenant(tenantId, key);
            boolean isRecordingOrEml = !isEmailAttachment && EmailAttachmentKeys.isRecordingKeyOwnedByTenant(tenantId, key);

            if (!isEmailAttachment && !isRecordingOrEml) {
                log.warn("[GDPR] Klucz S3 zwrócony przez anonymize_customer nie pasuje do żadnej znanej "
                                + "allow-listy tenanta — pomijam usuwanie: key={}",
                        EmailAttachmentKeys.forLog(key));
                failed.add(key);
                continue;
            }

            try {
                emailAttachmentStorageService.delete(key);
            } catch (RuntimeException e) {
                log.error("[GDPR] Nie udało się usunąć obiektu S3 po anonimizacji klienta (best-effort — "
                                + "PII w bazie już usunięte, kolejność DB→S3): key={}, error={}",
                        EmailAttachmentKeys.forLog(key), e.getMessage(), e);
                failed.add(key);
            }
        }

        if (!failed.isEmpty()) {
            log.error("[GDPR] Anonimizacja klienta zakończona, ale {}/{} obiektów S3 NIE zostało usuniętych "
                            + "— wymagana ręczna interwencja: keys={}",
                    failed.size(), s3Keys.size(),
                    failed.stream().map(EmailAttachmentKeys::forLog).toList());
        }
        return failed;
    }

    // =========================================================================
    // Budowa archiwum ZIP eksportu
    // =========================================================================

    private byte[] buildExportZip(UUID customerId, UUID tenantId, JsonNode exportResult) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            addJsonEntry(zos, "customer.json", exportResult.path("customer"));
            addJsonEntry(zos, "contacts.json", exportResult.path("contacts"));
            addJsonEntry(zos, "scheduled_callbacks.json", exportResult.path("scheduled_callbacks"));
            addJsonEntry(zos, "campaign_records.json", exportResult.path("campaign_records"));
            addJsonEntry(zos, "email_messages.json", exportResult.path("email_messages"));
            addJsonEntry(zos, "social_messages.json", exportResult.path("social_messages"));
            addJsonEntry(zos, "transcriptions.json", exportResult.path("transcriptions"));
            addJsonEntry(zos, "ai_summaries.json", exportResult.path("ai_summaries"));
            addJsonEntry(zos, "manifest.json", buildManifest(customerId, tenantId, exportResult));

            zos.finish();
            return baos.toByteArray();

        } catch (IOException e) {
            log.error("[GDPR] Błąd budowania archiwum ZIP dla customerId={}: {}",
                    customerId, e.getMessage(), e);
            throw new GdprException("Nie udało się zbudować archiwum ZIP dla klienta: "
                    + customerId, e);
        }
    }

    /**
     * Buduje {@code manifest.json}: metadane eksportu + lista obiektów S3 z presigned URL-ami.
     * Pliki audio/załączniki NIE trafiają do ZIP-a — wyłącznie odnośniki do pobrania.
     */
    private Map<String, Object> buildManifest(UUID customerId, UUID tenantId, JsonNode exportResult) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("customerId", customerId);
        manifest.put("tenantId", tenantId);
        manifest.put("exportGeneratedAt", exportResult.path("export_generated_at").asText(null));
        manifest.put("legalBasis", exportResult.path("legal_basis").asText(null));
        manifest.put("matchedByLink", exportResult.path("matched_by_link").asInt(0));
        manifest.put("matchedByIdentifier", exportResult.path("matched_by_identifier").asInt(0));
        manifest.put("s3Objects", buildS3Manifest(tenantId, exportResult.get("s3_keys")));
        return manifest;
    }

    /**
     * Generuje presigned URL dla każdego klucza S3 z eksportu, klasyfikując go do jednej z dwóch
     * allow-list (załącznik e-mail vs. nagranie/EML) — analogicznie do {@link #cleanupS3Objects}.
     * Błąd generowania URL dla pojedynczego klucza jest best-effort: wpis manifestu zostaje z
     * {@code presignedUrl = null} zamiast przerywać cały eksport.
     */
    private List<Map<String, Object>> buildS3Manifest(UUID tenantId, JsonNode s3KeysNode) {
        List<Map<String, Object>> manifest = new ArrayList<>();
        if (s3KeysNode == null || !s3KeysNode.isArray()) {
            return manifest;
        }

        Duration ttl = Duration.ofMinutes(s3Properties.getPresignedUrlExpirationMinutes());
        for (JsonNode keyNode : s3KeysNode) {
            String key = keyNode.asText(null);
            if (key == null || key.isBlank()) {
                continue;
            }

            String type = null;
            String presignedUrl = null;
            try {
                if (EmailAttachmentKeys.isOwnedByTenant(tenantId, key)) {
                    type = "EMAIL_ATTACHMENT";
                    presignedUrl = emailAttachmentStorageService.presignedDownloadUrl(key);
                } else if (EmailAttachmentKeys.isRecordingKeyOwnedByTenant(tenantId, key)) {
                    type = "RECORDING";
                    presignedUrl = recordingService.generatePresignedUrlForKey(key, ttl);
                } else {
                    log.warn("[GDPR] Klucz S3 z eksportu nie pasuje do znanej allow-listy — bez presigned "
                                    + "URL: key={}",
                            EmailAttachmentKeys.forLog(key));
                }
            } catch (RuntimeException e) {
                log.error("[GDPR] Nie udało się wygenerować presigned URL dla eksportu: key={}, error={}",
                        EmailAttachmentKeys.forLog(key), e.getMessage());
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("s3Key", key);
            entry.put("type", type);
            entry.put("presignedUrl", presignedUrl);
            manifest.add(entry);
        }
        return manifest;
    }

    private void addJsonEntry(ZipOutputStream zos, String fileName, Object data) throws IOException {
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
        ZipEntry entry = new ZipEntry(fileName);
        entry.setSize(json.length);
        zos.putNextEntry(entry);
        zos.write(json);
        zos.closeEntry();
    }

    // =========================================================================
    // Parsowanie JSONB zwróconego przez funkcje SQL
    // =========================================================================

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new GdprException("Nie udało się sparsować odpowiedzi JSON funkcji RODO", e);
        }
    }

    private List<String> jsonArrayToStrings(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(array.size());
        array.forEach(node -> {
            if (node.isTextual() && !node.asText().isBlank()) {
                result.add(node.asText());
            }
        });
        return List.copyOf(result);
    }

    // =========================================================================
    // Wyjątek domenowy
    // =========================================================================

    /** Wyjątek domenowy dla błędów operacji RODO. */
    public static class GdprException extends RuntimeException {
        public GdprException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
