package com.contactcenter.domain.customer;

import com.contactcenter.api.customer.dto.CustomerImportStatusResponse;
import com.contactcenter.api.customer.DeduplicationMode;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Serwis obsługujący asynchroniczny import klientów z pliku CSV (BE-026).
 *
 * <p>Przepływ:
 * <ol>
 *   <li>{@link #initiateImport} – waliduje plik, tworzy rekord statusu w Redis (QUEUED),
 *       pobiera snapshot TenantContext, uruchamia {@link #processImportAsync} w tle.</li>
 *   <li>{@link #processImportAsync} – parsuje CSV, waliduje telefony, batch-insertuje
 *       do tabeli {@code customer} (chunk po {@value #BATCH_SIZE} rekordów),
 *       deduplikuje po phone lub email (SKIP lub OVERWRITE),
 *       aktualizuje status co chunk. Na końcu: COMPLETED lub FAILED_PARTIAL.</li>
 * </ol>
 *
 * <p>Status joba przechowywany w Redis pod kluczem {@value #JOB_KEY_PREFIX}{jobId}
 * z TTL {@value #JOB_TTL_SECONDS}s (1h).
 *
 * <p>Kolumny CSV: first_name, last_name, phone (wielokrotne wartości rozdzielone ';'),
 * email (wielokrotne wartości rozdzielone ';'), custom_fields.
 *
 * <p>Walidacja telefonu: format E.164 {@code +[1-15 cyfr]}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class CustomerImportServiceImpl implements CustomerImportService {

    // =========================================================================
    // Stałe
    // =========================================================================

    /** Maksymalny rozmiar pliku CSV: 50 MB. */
    public static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;

    /** Rozmiar chunka batch insertu. */
    public static final int BATCH_SIZE = 500;

    /** Prefiks klucza Redis dla statusu joba. */
    public static final String JOB_KEY_PREFIX = "import:customer:";

    /** TTL statusu joba w Redis: 1 godzina. */
    public static final long JOB_TTL_SECONDS = 3600L;

    /** Sufiks klucza Redis dla raportu błędów (CSV). */
    public static final String ERRORS_KEY_SUFFIX = ":errors";

    /** Wzorzec E.164: + i 1-15 cyfr. */
    private static final Pattern E164_PATTERN = Pattern.compile("^\\+[1-9]\\d{0,14}$");

    /** Separator wartości wielokrotnych (phone, email) w jednej komórce CSV. */
    private static final String MULTI_VALUE_SEPARATOR = ";";

    private static final String COL_FIRST_NAME = "first_name";
    private static final String COL_LAST_NAME  = "last_name";
    private static final String COL_PHONE      = "phone";
    private static final String COL_EMAIL      = "email";
    private static final String COL_CUSTOM     = "custom_fields";

    // =========================================================================
    // Zależności
    // =========================================================================

    private final CustomerRepository customerRepository;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // Inicjowanie importu (synchroniczne – szybkie)
    // =========================================================================

    /**
     * Waliduje plik i inicjuje asynchroniczny import klientów.
     *
     * @param file              plik CSV (multipart)
     * @param deduplicationMode strategia deduplikacji
     * @param columnSeparator   separator kolumn
     * @param quoteChar         znak cytowania
     * @param columnMappingJson mapowanie kolumn CSV → pole systemu (JSON string) lub null
     * @return UUID joba – do pollingu statusu
     * @throws IllegalArgumentException gdy plik nie spełnia wymagań
     */
    @Override
    public UUID initiateImport(MultipartFile file, DeduplicationMode deduplicationMode,
                               String columnSeparator, String quoteChar, String columnMappingJson) {
        UUID tenantId = TenantContext.getTenantId();

        validateFile(file);

        UUID jobId = UUID.randomUUID();

        // Zapisz status QUEUED w Redis
        saveJobState(jobId, buildInitialState(jobId, deduplicationMode));

        log.info("[CustomerImport] Import zainicjowany: jobId={}, tenant={}, mode={}, sep='{}', quote='{}'",
                jobId, tenantId, deduplicationMode, columnSeparator, quoteChar);

        // Snapshot kontekstu tenanta przed uruchomieniem wątku async
        TenantContext.Snapshot snapshot = TenantContext.snapshot();

        processImportAsync(jobId, file, deduplicationMode, columnSeparator, quoteChar, columnMappingJson, snapshot);

        return jobId;
    }

    // =========================================================================
    // Przetwarzanie asynchroniczne
    // =========================================================================

    /**
     * Asynchroniczne przetwarzanie importu CSV.
     *
     * <p>Uruchamiana w puli wątków (konfiguracja AsyncConfig).
     * Propagacja TenantContext przez snapshot/restore.
     */
    @Async("applicationTaskExecutor")
    public void processImportAsync(UUID jobId, MultipartFile file, DeduplicationMode deduplicationMode,
                                   String columnSeparator, String quoteChar, String columnMappingJson,
                                   TenantContext.Snapshot snapshot) {
        TenantContext.restore(snapshot);
        try {
            UUID tenantId = TenantContext.getTenantId();

            log.info("[CustomerImport] Przetwarzanie: jobId={}, tenant={}", jobId, tenantId);

            // Przejście do PROCESSING
            Map<String, Object> state = loadJobState(jobId);
            if (state == null) {
                log.error("[CustomerImport] Brak statusu joba w Redis: jobId={}", jobId);
                return;
            }
            state.put("status", "PROCESSING");
            state.put("startedAt", Instant.now().toString());
            saveJobState(jobId, state);

            doImport(jobId, tenantId, file, deduplicationMode, columnSeparator, quoteChar, columnMappingJson, state);

        } catch (Exception e) {
            log.error("[CustomerImport] Nieoczekiwany błąd importu: jobId={}, error={}", jobId, e.getMessage(), e);
            try {
                Map<String, Object> state = loadJobState(jobId);
                if (state != null) {
                    state.put("status", "FAILED_PARTIAL");
                    state.put("completedAt", Instant.now().toString());
                    appendError(state, 0, "FATAL: " + e.getMessage(), "");
                    saveJobState(jobId, state);
                }
            } catch (Exception ex) {
                log.error("[CustomerImport] Nie można zapisać błędu statusu joba: jobId={}", jobId, ex);
            }
        } finally {
            TenantContext.clear();
        }
    }

    // =========================================================================
    // Logika importu
    // =========================================================================

    @SuppressWarnings("unchecked")
    private void doImport(UUID jobId, UUID tenantId, MultipartFile file,
                          DeduplicationMode mode, String columnSeparator, String quoteChar,
                          String columnMappingJson, Map<String, Object> state) throws IOException {

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        int totalRows = 0;
        int imported  = 0;
        int updated   = 0;
        int skipped   = 0;
        int failed    = 0;
        int rowNumber = 0;
        boolean hasHeader = false;
        Map<String, Integer> columnIndex = new HashMap<>();

        Map<String, Integer> explicitMapping = parseColumnMappingJson(columnMappingJson);

        char sep   = resolveSeparator(columnSeparator);
        char quote = resolveQuote(quoteChar);

        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {

            CSVParserBuilder parserBuilder = new CSVParserBuilder().withSeparator(sep);
            if (quote != '\0') {
                parserBuilder.withQuoteChar(quote);
            } else {
                parserBuilder.withIgnoreQuotations(true);
            }
            CSVParser parser = parserBuilder.build();
            CSVReader csvReader = new CSVReaderBuilder(reader).withCSVParser(parser).build();

            if (!explicitMapping.isEmpty()) {
                columnIndex = explicitMapping;
                log.debug("[CustomerImport] Mapowanie z parametru: {}", columnIndex);
            }

            String[] line;
            while ((line = csvReader.readNext()) != null) {
                rowNumber++;

                // Auto-detekcja nagłówka w pierwszym wierszu
                if (rowNumber == 1 && explicitMapping.isEmpty()) {
                    if (isHeader(line)) {
                        hasHeader = true;
                        columnIndex = buildColumnIndex(line);
                        log.debug("[CustomerImport] Wykryto nagłówek: {}", columnIndex);
                        continue;
                    }
                    columnIndex = defaultColumnIndex();
                }

                totalRows++;
                int dataRow = hasHeader ? rowNumber - 1 : rowNumber;

                CsvRow csvRow = parseRow(line, columnIndex);

                // Walidacja – wymaga przynajmniej jednego poprawnego telefonu
                List<String> validPhones = filterValidPhones(csvRow.phones());
                if (validPhones.isEmpty()) {
                    String rawPhones = csvRow.phones() != null ? String.join(";", csvRow.phones()) : "(empty)";
                    String reason = "Brak poprawnego numeru telefonu E.164";
                    log.debug("[CustomerImport] Odrzucono wiersz {}: {}, phone='{}'", dataRow, reason, rawPhones);
                    appendError(state, dataRow, reason, String.join(",", line));
                    failed++;
                    continue;
                }

                // Szukaj istniejącego klienta po phone lub email
                Optional<Customer> existing = findExisting(tenantId, validPhones, csvRow.emails());

                if (existing.isPresent()) {
                    if (mode == DeduplicationMode.SKIP) {
                        skipped++;
                        log.debug("[CustomerImport] SKIP duplikat: wiersz={}", dataRow);
                        continue;
                    }
                    // OVERWRITE – wstaw do batcha z flagą aktualizacji
                    batch.add(buildUpdateRow(existing.get().getCustomerId(), tenantId, csvRow, validPhones));
                    updated++;
                } else {
                    batch.add(buildInsertRow(tenantId, csvRow, validPhones));
                    imported++;
                }

                // Flush batcha po BATCH_SIZE wierszach
                if (batch.size() >= BATCH_SIZE) {
                    flushBatch(batch, mode);
                    batch.clear();

                    // Aktualizuj postęp w Redis co chunk
                    state.put("processed", totalRows);
                    state.put("imported",  imported);
                    state.put("updated",   updated);
                    state.put("skipped",   skipped);
                    state.put("failed",    failed);
                    saveJobState(jobId, state);

                    log.debug("[CustomerImport] Chunk: jobId={}, przetworzono={}, zaimportowano={}, zaktualizowano={}",
                            jobId, totalRows, imported, updated);
                }
            }

            // Flush pozostałości
            if (!batch.isEmpty()) {
                flushBatch(batch, mode);
            }
        } catch (Exception e) {
            throw new IOException("Błąd parsowania CSV: " + e.getMessage(), e);
        }

        // Finalizacja statusu
        String finalStatus = failed > 0 ? "FAILED_PARTIAL" : "COMPLETED";
        state.put("status",      finalStatus);
        state.put("total",       totalRows);
        state.put("processed",   totalRows);
        state.put("imported",    imported);
        state.put("updated",     updated);
        state.put("skipped",     skipped);
        state.put("failed",      failed);
        state.put("completedAt", Instant.now().toString());
        state.put("errorFileAvailable", failed > 0);
        saveJobState(jobId, state);

        // Zapisz raport błędów jako CSV w Redis jeśli są błędy
        if (failed > 0) {
            saveErrorReport(jobId, (List<String[]>) state.get("errors"));
        }

        log.info("[CustomerImport] Import zakończony: jobId={}, status={}, total={}, imported={}, updated={}, skipped={}, failed={}",
                jobId, finalStatus, totalRows, imported, updated, skipped, failed);
    }

    // =========================================================================
    // Batch insert / update
    // =========================================================================

    private void flushBatch(List<Object[]> batch, DeduplicationMode mode) {
        // Rozdziel batch na wstawki i aktualizacje (rozróżnione typem pierwszego elementu)
        List<Object[]> inserts = new ArrayList<>();
        List<Object[]> updates = new ArrayList<>();

        for (Object[] row : batch) {
            if (row[0] == null) {
                inserts.add(row); // nowy klient: row[0]=null (brak customerId)
            } else {
                updates.add(row); // aktualizacja: row[0]=customerId
            }
        }

        if (!inserts.isEmpty()) {
            batchInsertCustomers(inserts);
        }
        if (!updates.isEmpty()) {
            batchUpdateCustomers(updates);
        }
    }

    private void batchInsertCustomers(List<Object[]> rows) {
        String sql = """
                INSERT INTO customer
                    (customer_id, tenant_id, first_name, last_name, phone, email,
                     custom_fields, gdpr_consent, source, is_deleted, created_at)
                VALUES
                    (gen_random_uuid(), CAST(? AS uuid), ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
                     CAST(? AS jsonb), '{}'::jsonb, 'CSV_IMPORT', false, ?)
                ON CONFLICT DO NOTHING
                """;

        jdbcTemplate.batchUpdate(sql, rows);
        log.debug("[CustomerImport] Batch INSERT: {} klientów", rows.size());
    }

    private void batchUpdateCustomers(List<Object[]> rows) {
        String sql = """
                UPDATE customer
                SET first_name    = ?,
                    last_name     = ?,
                    phone         = CAST(? AS jsonb),
                    email         = CAST(? AS jsonb),
                    custom_fields = CAST(? AS jsonb),
                    updated_at    = ?
                WHERE customer_id = CAST(? AS uuid)
                  AND tenant_id   = CAST(? AS uuid)
                  AND is_deleted  = false
                """;

        // Przetasuj kolejność parametrów dla UPDATE
        // row: [customerId, tenantId, firstName, lastName, phonesJson, emailsJson, customFieldsJson, updatedAt]
        List<Object[]> updateParams = new ArrayList<>();
        for (Object[] row : rows) {
            updateParams.add(new Object[]{
                    row[2], // first_name
                    row[3], // last_name
                    row[4], // phone (jsonb)
                    row[5], // email (jsonb)
                    row[6], // custom_fields (jsonb)
                    row[7], // updated_at
                    row[0], // customer_id (WHERE)
                    row[1]  // tenant_id (WHERE)
            });
        }

        jdbcTemplate.batchUpdate(sql, updateParams);
        log.debug("[CustomerImport] Batch UPDATE: {} klientów", rows.size());
    }

    // =========================================================================
    // Szukanie istniejących klientów
    // =========================================================================

    private Optional<Customer> findExisting(UUID tenantId, List<String> phones, List<String> emails) {
        // Szukaj po każdym telefonie
        for (String phone : phones) {
            Optional<Customer> found = customerRepository.findByPhoneNumber(phone, tenantId);
            if (found.isPresent()) {
                return found;
            }
        }
        // Szukaj po każdym emailu
        for (String email : emails) {
            Optional<Customer> found = customerRepository.findByEmail(email, tenantId);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    // =========================================================================
    // Budowanie wierszy batch
    // =========================================================================

    /**
     * Buduje wiersz dla INSERT (customerId=null jako znacznik).
     * Kolejność: [null, tenantId, firstName, lastName, phonesJson, emailsJson, customFieldsJson, createdAt]
     */
    private Object[] buildInsertRow(UUID tenantId, CsvRow csvRow, List<String> validPhones) {
        List<String> allEmails = csvRow.emails() != null ? csvRow.emails() : List.of();
        return new Object[]{
                null,                          // [0] customerId = null → INSERT
                tenantId.toString(),           // [1]
                csvRow.firstName(),            // [2]
                csvRow.lastName(),             // [3]
                toJsonArray(validPhones),      // [4] phone (JSONB)
                toJsonArray(allEmails),        // [5] email (JSONB)
                toJson(csvRow.customFields()), // [6] custom_fields
                Timestamp.from(Instant.now()) // [7] created_at
        };
    }

    /**
     * Buduje wiersz dla UPDATE.
     * Kolejność: [customerId, tenantId, firstName, lastName, phonesJson, emailsJson, customFieldsJson, updatedAt]
     */
    private Object[] buildUpdateRow(UUID customerId, UUID tenantId, CsvRow csvRow, List<String> validPhones) {
        List<String> allEmails = csvRow.emails() != null ? csvRow.emails() : List.of();
        return new Object[]{
                customerId.toString(),         // [0] customerId → UPDATE
                tenantId.toString(),           // [1]
                csvRow.firstName(),            // [2]
                csvRow.lastName(),             // [3]
                toJsonArray(validPhones),      // [4] phone (JSONB)
                toJsonArray(allEmails),        // [5] email (JSONB)
                toJson(csvRow.customFields()), // [6] custom_fields
                Timestamp.from(Instant.now()) // [7] updated_at
        };
    }

    // =========================================================================
    // Raport błędów
    // =========================================================================

    @SuppressWarnings("unchecked")
    private void appendError(Map<String, Object> state, int lineNumber, String message, String rawData) {
        List<String[]> errors = (List<String[]>) state.computeIfAbsent("errors", k -> new ArrayList<>());
        errors.add(new String[]{String.valueOf(lineNumber), message, rawData});
        state.put("errorFileAvailable", true);
    }

    @SuppressWarnings("unchecked")
    private void saveErrorReport(UUID jobId, List<String[]> errors) {
        if (errors == null || errors.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder("line_number,error_message,raw_data\n");
        for (String[] err : errors) {
            sb.append(escapeCsvField(err[0])).append(",")
              .append(escapeCsvField(err[1])).append(",")
              .append(escapeCsvField(err[2])).append("\n");
        }
        String key = JOB_KEY_PREFIX + jobId + ERRORS_KEY_SUFFIX;
        stringRedisTemplate.opsForValue().set(key, sb.toString(), Duration.ofSeconds(JOB_TTL_SECONDS));
    }

    private String escapeCsvField(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // =========================================================================
    // Pobieranie statusu / błędów (wywołania z kontrolera)
    // =========================================================================

    /**
     * Pobiera status joba z Redis jako DTO do kontrolera.
     *
     * @param jobId UUID joba
     * @return DTO statusu lub null gdy nie istnieje
     */
    @Override
    public CustomerImportStatusResponse getJobStatus(UUID jobId) {
        Map<String, Object> state = loadJobState(jobId);
        if (state == null) {
            return null;
        }
        return mapStateToResponse(state);
    }

    /**
     * Pobiera raport błędów jako CSV bytes z Redis.
     *
     * @param jobId UUID joba
     * @return bytes CSV lub null gdy brak błędów / job nieznany
     */
    @Override
    public byte[] getErrorReport(UUID jobId) {
        String key = JOB_KEY_PREFIX + jobId + ERRORS_KEY_SUFFIX;
        String csv = stringRedisTemplate.opsForValue().get(key);
        if (csv == null) {
            return null;
        }
        return csv.getBytes(StandardCharsets.UTF_8);
    }

    // =========================================================================
    // Redis – helper
    // =========================================================================

    private void saveJobState(UUID jobId, Map<String, Object> state) {
        try {
            // Usuń listę błędów z payload (przechowywana osobno przez saveErrorReport)
            Map<String, Object> redisState = new LinkedHashMap<>(state);
            redisState.remove("errors");

            String key  = JOB_KEY_PREFIX + jobId;
            String json = objectMapper.writeValueAsString(redisState);
            stringRedisTemplate.opsForValue().set(key, json, Duration.ofSeconds(JOB_TTL_SECONDS));
        } catch (Exception e) {
            log.error("[CustomerImport] Błąd zapisu statusu do Redis: jobId={}, error={}", jobId, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadJobState(UUID jobId) {
        try {
            String key  = JOB_KEY_PREFIX + jobId;
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.error("[CustomerImport] Błąd odczytu statusu z Redis: jobId={}, error={}", jobId, e.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildInitialState(UUID jobId, DeduplicationMode mode) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("jobId",  jobId.toString());
        state.put("status", "QUEUED");
        state.put("deduplicationMode", mode.name());
        state.put("processed", 0);
        state.put("total",     0);
        state.put("imported",  0);
        state.put("updated",   0);
        state.put("skipped",   0);
        state.put("failed",    0);
        state.put("errorFileAvailable", false);
        return state;
    }

    @SuppressWarnings("unchecked")
    private CustomerImportStatusResponse mapStateToResponse(Map<String, Object> state) {
        return new CustomerImportStatusResponse(
                (String)  state.getOrDefault("jobId",  ""),
                (String)  state.getOrDefault("status", "UNKNOWN"),
                toInt(state.get("processed")),
                toInt(state.get("total")),
                toInt(state.get("imported")),
                toInt(state.get("updated")),
                toInt(state.get("skipped")),
                toInt(state.get("failed")),
                (Boolean) state.getOrDefault("errorFileAvailable", false)
        );
    }

    private int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return 0;
    }

    // =========================================================================
    // Walidacja pliku
    // =========================================================================

    /**
     * Waliduje plik CSV: rozszerzenie i rozmiar.
     *
     * @throws IllegalArgumentException gdy plik nie spełnia wymagań
     */
    public void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Plik CSV jest wymagany i nie może być pusty.");
        }

        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".csv")) {
            throw new IllegalArgumentException(
                    "Dozwolone są tylko pliki CSV (.csv). Przesłany plik: " + name);
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    String.format("Plik CSV jest za duży: %d MB. Maksymalny dozwolony rozmiar: 50 MB.",
                            file.getSize() / (1024 * 1024)));
        }
    }

    // =========================================================================
    // Walidacja telefonu
    // =========================================================================

    /**
     * Waliduje numer telefonu w formacie E.164: +[1-15 cyfr].
     */
    public boolean isValidE164(String phone) {
        if (phone == null || phone.isBlank()) {
            return false;
        }
        return E164_PATTERN.matcher(phone.trim()).matches();
    }

    private List<String> filterValidPhones(List<String> phones) {
        if (phones == null || phones.isEmpty()) {
            return List.of();
        }
        return phones.stream()
                .filter(p -> p != null && isValidE164(p.trim()))
                .map(String::trim)
                .toList();
    }

    // =========================================================================
    // Parsowanie CSV
    // =========================================================================

    private boolean isHeader(String[] line) {
        for (String col : line) {
            String lower = col.trim().toLowerCase();
            if (lower.equals(COL_PHONE) || lower.equals(COL_FIRST_NAME)
                    || lower.equals(COL_LAST_NAME) || lower.equals(COL_EMAIL)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Integer> buildColumnIndex(String[] header) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            index.put(header[i].trim().toLowerCase(), i);
        }
        return index;
    }

    private Map<String, Integer> defaultColumnIndex() {
        Map<String, Integer> index = new HashMap<>();
        index.put(COL_FIRST_NAME, 0);
        index.put(COL_LAST_NAME,  1);
        index.put(COL_PHONE,      2);
        index.put(COL_EMAIL,      3);
        return index;
    }

    private CsvRow parseRow(String[] line, Map<String, Integer> columnIndex) {
        String firstName = getColumn(line, columnIndex, COL_FIRST_NAME);
        String lastName  = getColumn(line, columnIndex, COL_LAST_NAME);
        String phoneRaw  = getColumn(line, columnIndex, COL_PHONE);
        String emailRaw  = getColumn(line, columnIndex, COL_EMAIL);
        String customRaw = getColumn(line, columnIndex, COL_CUSTOM);

        List<String> phones = splitMultiValue(phoneRaw);
        List<String> emails = splitMultiValue(emailRaw);

        Map<String, String> customFields = parseCustomFields(customRaw, columnIndex, line);

        return new CsvRow(firstName, lastName, phones, emails, customFields);
    }

    /**
     * Parsuje custom_fields: jeśli kolumna "custom_fields" istnieje w nagłówku – parsuje jako JSON,
     * w przeciwnym wypadku zbiera wszystkie kolumny zaczynające się na "custom_".
     */
    private Map<String, String> parseCustomFields(String customRaw,
                                                   Map<String, Integer> columnIndex,
                                                   String[] line) {
        Map<String, String> result = new LinkedHashMap<>();

        if (customRaw != null && !customRaw.isBlank()) {
            // Spróbuj parsować jako JSON
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed = objectMapper.readValue(customRaw, Map.class);
                parsed.forEach((k, v) -> {
                    if (v != null) result.put(k, v.toString());
                });
                return result;
            } catch (Exception ignored) {
                // Nie jest JSON – traktuj jako zwykłą wartość
                result.put("custom_fields", customRaw);
                return result;
            }
        }

        // Zbierz kolumny zaczynające się na "custom_"
        for (Map.Entry<String, Integer> entry : columnIndex.entrySet()) {
            if (entry.getKey().startsWith("custom_") && !entry.getKey().equals(COL_CUSTOM)) {
                String val = getColumn(line, columnIndex, entry.getKey());
                if (val != null && !val.isBlank()) {
                    result.put(entry.getKey(), val);
                }
            }
        }

        return result;
    }

    private String getColumn(String[] line, Map<String, Integer> idx, String colName) {
        Integer i = idx.get(colName);
        if (i == null || i >= line.length) {
            return null;
        }
        String val = line[i].trim();
        return val.isEmpty() ? null : val;
    }

    /**
     * Rozdziela wartości wielokrotne rozdzielone średnikiem.
     * Np. "+48123456789;+48987654321" → ["+48123456789", "+48987654321"]
     */
    private List<String> splitMultiValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String part : raw.split(MULTI_VALUE_SEPARATOR)) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /**
     * Parsuje JSON mapowania kolumn z frontendu.
     *
     * <p>Format wejściowy: {@code {"nagłówek_csv": "pole_systemu", ...}}
     * gdzie wartość to indeks kolumny CSV (0-based).
     *
     * @param json JSON string lub null/pusty
     * @return mapa nagłówek_csv → indeks kolumny; pusta gdy json jest null/pusty/błędny
     */
    @SuppressWarnings("unchecked")
    public Map<String, Integer> parseColumnMappingJson(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            Map<String, Integer> result = new HashMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                if (entry.getValue() instanceof Number num) {
                    result.put(entry.getKey(), num.intValue());
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("[CustomerImport] Błąd parsowania columnMapping JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    // =========================================================================
    // Serializacja JSON
    // =========================================================================

    private String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("[CustomerImport] Błąd serializacji listy do JSON: {}", e.getMessage());
            return "[]";
        }
    }

    private String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("[CustomerImport] Błąd serializacji custom_fields: {}", e.getMessage());
            return "{}";
        }
    }

    // =========================================================================
    // Pomocnicze – separator i znak cytowania
    // =========================================================================

    private char resolveSeparator(String sep) {
        if (sep == null || sep.isEmpty()) return ',';
        if ("\\t".equals(sep)) return '\t';
        return sep.charAt(0);
    }

    private char resolveQuote(String quote) {
        if (quote == null || quote.isEmpty()) return '\0';
        return quote.charAt(0);
    }

    // =========================================================================
    // Wewnętrzny record – model wiersza CSV
    // =========================================================================

    private record CsvRow(
            String firstName,
            String lastName,
            List<String> phones,
            List<String> emails,
            Map<String, String> customFields
    ) {}
}
