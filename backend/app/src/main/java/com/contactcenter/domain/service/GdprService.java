package com.contactcenter.domain.service;

import com.contactcenter.domain.model.AuditLogEvent;
import com.contactcenter.domain.model.Contact;
import com.contactcenter.domain.customer.Customer;
import com.contactcenter.domain.repository.ContactRepository;
import com.contactcenter.domain.customer.CustomerService;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Serwis realizujący operacje RODO (BE-031).
 *
 * <p>Implementuje dwa prawa podmiotów danych:
 * <ul>
 *   <li>Art. 20 – Prawo do przenoszenia danych: {@link #exportCustomerData(UUID)} tworzy
 *       archiwum ZIP zawierające dane klienta, historię kontaktów i log audytowy.</li>
 *   <li>Art. 17 – Prawo do bycia zapomnianym: {@link #anonymizeCustomer(UUID)} usuwa PII
 *       klienta, usuwa nagrania z S3 i oznacza rekord jako is_deleted=true.</li>
 * </ul>
 *
 * <p>Obie operacje logowane są w AUDIT_LOG przez {@link AuditLogService} (fire-and-forget).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GdprService {

    private final CustomerService customerService;
    private final ContactRepository contactRepository;
    private final RecordingService recordingService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // Art. 20 – Eksport danych klienta
    // =========================================================================

    /**
     * Eksportuje wszystkie dane klienta do archiwum ZIP (RODO Art. 20).
     *
     * <p>Archiwum zawiera:
     * <ul>
     *   <li>{@code customer.json} – dane profilu klienta (PII)</li>
     *   <li>{@code contacts.json} – pełna historia kontaktów</li>
     *   <li>{@code audit_log.json} – metadane zdarzenia eksportu</li>
     * </ul>
     *
     * <p>Pliki audio nagrań nie są dołączane – dostęp przez presigned URL (BE-010).
     *
     * @param customerId UUID klienta
     * @return bajty archiwum ZIP
     * @throws EntityNotFoundException gdy klient nie istnieje w tenancie
     * @throws GdprException           gdy nie uda się zbudować archiwum ZIP
     */
    public byte[] exportCustomerData(UUID customerId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId   = TenantContext.getUserId();

        log.info("[GDPR] Export danych klienta: customerId={}, tenant={}, requestedBy={}",
                customerId, tenantId, userId);

        Customer customer = customerService.findById(customerId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Klient nie istnieje lub nie należy do bieżącego tenanta: " + customerId));

        // Pobierz historię kontaktów (do 1000 rekordów – limit ochronny)
        List<Contact> contacts = contactRepository.findByCustomerId(customerId, tenantId, 0, 1000);

        byte[] zipBytes = buildExportZip(customer, contacts);

        // Loguj zdarzenie audytowe asynchronicznie (fire-and-forget)
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

    /**
     * Anonimizuje dane osobowe klienta zgodnie z RODO Art. 17 (prawo do bycia zapomnianym).
     *
     * <p>Sekwencja operacji:
     * <ol>
     *   <li>Pobierz listę kluczy S3 nagrań klienta.</li>
     *   <li>Usuń nagrania z S3 (best-effort – błąd nie przerywa anonimizacji).</li>
     *   <li>Anonimizuj rekord klienta w DB (first_name='ANONYMIZED', last_name='ANONYMIZED',
     *       phone=[], email=[], is_deleted=true).</li>
     *   <li>Zaloguj zdarzenie GDPR_ANONYMIZE w AUDIT_LOG.</li>
     * </ol>
     *
     * <p>Rekord customer pozostaje w bazie – historia kontaktów zachowana dla celów
     * rozliczeniowych (dane nie zawierają już PII po anonimizacji).
     *
     * @param customerId UUID klienta do anonimizacji
     * @throws EntityNotFoundException gdy klient nie istnieje lub jest już zanonimizowany
     */
    public void anonymizeCustomer(UUID customerId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId   = TenantContext.getUserId();

        log.info("[GDPR] Anonimizacja klienta: customerId={}, tenant={}, requestedBy={}",
                customerId, tenantId, userId);

        // Weryfikuj istnienie klienta przed anonimizacją
        customerService.findById(customerId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Klient nie istnieje lub jest już zanonimizowany: " + customerId));

        // Usuń nagrania z S3 (best-effort – błąd nie blokuje anonimizacji PII)
        deleteCustomerRecordingsFromS3(customerId, tenantId);

        // Anonimizuj rekord klienta w bazie danych
        int updated = customerService.anonymize(customerId, tenantId);
        if (updated == 0) {
            // Może wystąpić race condition – inny wątek anonimizował równolegle
            log.warn("[GDPR] Anonimizacja nie zaktualizowała żadnego rekordu: customerId={}, tenant={}",
                    customerId, tenantId);
            throw new EntityNotFoundException(
                    "Klient nie istnieje lub jest już zanonimizowany: " + customerId);
        }

        // Loguj zdarzenie audytowe asynchronicznie
        auditLogService.publishAuditEvent(new AuditLogEvent(
                tenantId,
                userId,
                "GDPR_ANONYMIZE",
                "CUSTOMER",
                customerId,
                null,
                null,
                null,
                null,
                Instant.now()
        ));

        log.info("[GDPR] Klient zanonimizowany: customerId={}, tenant={}", customerId, tenantId);
    }

    // =========================================================================
    // Metody prywatne
    // =========================================================================

    /**
     * Buduje archiwum ZIP z danymi eksportu klienta.
     *
     * @param customer encja klienta
     * @param contacts lista kontaktów klienta
     * @return bajty archiwum ZIP
     * @throws GdprException gdy nie uda się zserializować danych lub zbudować ZIP
     */
    private byte[] buildExportZip(Customer customer, List<Contact> contacts) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            // customer.json
            addJsonEntry(zos, "customer.json", customer);

            // contacts.json
            addJsonEntry(zos, "contacts.json", contacts);

            // audit_log.json – metadane eksportu
            ExportMetadata metadata = new ExportMetadata(
                    customer.getCustomerId(),
                    customer.getTenantId(),
                    Instant.now(),
                    "GDPR_EXPORT",
                    contacts.size()
            );
            addJsonEntry(zos, "audit_log.json", metadata);

            zos.finish();
            return baos.toByteArray();

        } catch (IOException e) {
            log.error("[GDPR] Błąd budowania archiwum ZIP dla customerId={}: {}",
                    customer.getCustomerId(), e.getMessage(), e);
            throw new GdprException("Nie udało się zbudować archiwum ZIP dla klienta: "
                    + customer.getCustomerId(), e);
        }
    }

    /**
     * Serializuje obiekt do JSON i zapisuje jako wpis w archiwum ZIP.
     *
     * @param zos      strumień ZIP
     * @param fileName nazwa wpisu (np. customer.json)
     * @param data     obiekt do serializacji
     */
    private void addJsonEntry(ZipOutputStream zos, String fileName, Object data) throws IOException {
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
        ZipEntry entry = new ZipEntry(fileName);
        entry.setSize(json.length);
        zos.putNextEntry(entry);
        zos.write(json);
        zos.closeEntry();
    }

    /**
     * Usuwa nagrania klienta z S3 (best-effort).
     *
     * <p>Błędy przy usuwaniu poszczególnych plików są logowane, ale nie przerywają
     * procesu anonimizacji – PII zostanie usunięte z bazy niezależnie od błędów S3.
     *
     * @param customerId UUID klienta
     * @param tenantId   UUID tenanta
     */
    private void deleteCustomerRecordingsFromS3(UUID customerId, UUID tenantId) {
        List<String> recordingUrls = contactRepository.findRecordingUrlsByCustomer(customerId, tenantId);

        if (recordingUrls.isEmpty()) {
            log.debug("[GDPR] Brak nagrań do usunięcia: customerId={}", customerId);
            return;
        }

        log.info("[GDPR] Usuwanie {} nagrań z S3 dla klienta: customerId={}",
                recordingUrls.size(), customerId);

        int deleted = 0;
        int failed = 0;
        for (String s3Key : recordingUrls) {
            try {
                recordingService.deleteFromS3(s3Key);
                deleted++;
            } catch (Exception e) {
                // best-effort – kontynuuj usuwanie pozostałych plików
                log.error("[GDPR] Nie udało się usunąć nagrania S3: key={}, error={}",
                        s3Key, e.getMessage());
                failed++;
            }
        }

        log.info("[GDPR] Nagrania usunięte: deleted={}, failed={}, customerId={}",
                deleted, failed, customerId);
    }

    // =========================================================================
    // Pomocniczy record dla metadanych eksportu
    // =========================================================================

    /**
     * Metadane audytowe eksportu danych – wpisywane do audit_log.json w archiwum ZIP.
     */
    public record ExportMetadata(
            UUID customerId,
            UUID tenantId,
            Instant exportedAt,
            String reason,
            int contactsCount
    ) {}

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
