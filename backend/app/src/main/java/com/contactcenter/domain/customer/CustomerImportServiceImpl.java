package com.contactcenter.domain.customer;

import com.contactcenter.api.customer.dto.CustomerImportStatusResponse;
import com.contactcenter.api.customer.DeduplicationMode;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
 * <p>Kolumny CSV: first_name, last_name, phone (wielokrotne wartości/kolumny – rozdzielone ';'
 * w jednej komórce lub zmapowane z wielu kolumn CSV), email (analogicznie wielokrotne),
 * external_id (opcjonalny, unikalny w tenancie), custom_fields (nazwane pola dodatkowe –
 * mapowane jako obiekt {@code {nazwa: indeks_kolumny}} lub jedna kolumna z surowym JSON-em –
 * wsteczna zgodność), consent_given / marketing_consent (opcjonalna zgoda RODO – zapisywana
 * do {@code gdpr_consent} z {@code consent_source=CSV_IMPORT} i {@code consent_date}).
 *
 * <p>Mapowanie kolumn ({@code columnMappingJson}) obsługuje zarówno pojedynczy indeks kolumny
 * na pole (jak dotychczas), jak i tablicę indeksów dla {@code phone}/{@code email} (łączenie
 * wielu kolumn CSV w jedną listę wartości) oraz obiekt {@code {nazwa_pola: indeks}} dla
 * {@code custom_fields}.
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

    /** Maksymalny rozmiar pliku CSV/JSON: 50 MB. */
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

    private static final String COL_FIRST_NAME  = "first_name";
    private static final String COL_LAST_NAME   = "last_name";
    private static final String COL_PHONE       = "phone";
    private static final String COL_EMAIL       = "email";
    private static final String COL_CUSTOM      = "custom_fields";
    private static final String COL_EXTERNAL_ID = "external_id";
    private static final String COL_CONSENT_GIVEN     = "consent_given";
    private static final String COL_MARKETING_CONSENT = "marketing_consent";

    /** Źródło zgody RODO zapisywane przy imporcie CSV. */
    private static final String CONSENT_SOURCE_CSV_IMPORT = "CSV_IMPORT";

    /** Źródło zgody RODO zapisywane przy imporcie JSON. */
    private static final String CONSENT_SOURCE_JSON_IMPORT = "JSON_IMPORT";

    /** Klucze zagnieżdżonego obiektu {@code gdprConsent} w wierszu JSON. */
    private static final String JSON_KEY_CONSENT_GIVEN     = "consent_given";
    private static final String JSON_KEY_MARKETING_CONSENT = "marketing_consent";

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

    /**
     * Waliduje plik i inicjuje asynchroniczny import klientów z pliku JSON (tablica obiektów
     * camelCase, zgodna z {@code CreateCustomerRequest}/{@code CustomerResponse}).
     *
     * @param file              plik JSON (multipart) – tablica obiektów klientów
     * @param deduplicationMode strategia deduplikacji
     * @return UUID joba – do pollingu statusu (ten sam mechanizm co import CSV)
     * @throws IllegalArgumentException gdy plik nie spełnia wymagań
     */
    @Override
    public UUID initiateJsonImport(MultipartFile file, DeduplicationMode deduplicationMode) {
        UUID tenantId = TenantContext.getTenantId();

        validateJsonFile(file);

        UUID jobId = UUID.randomUUID();

        // Zapisz status QUEUED w Redis
        saveJobState(jobId, buildInitialState(jobId, deduplicationMode));

        log.info("[CustomerImport] Import JSON zainicjowany: jobId={}, tenant={}, mode={}",
                jobId, tenantId, deduplicationMode);

        // Snapshot kontekstu tenanta przed uruchomieniem wątku async
        TenantContext.Snapshot snapshot = TenantContext.snapshot();

        processJsonImportAsync(jobId, file, deduplicationMode, snapshot);

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

    /**
     * Asynchroniczne przetwarzanie importu JSON.
     *
     * <p>Mirror {@link #processImportAsync} – tożsamy przepływ statusów i obsługa błędów,
     * różni się jedynie wywołaniem {@link #doJsonImport} zamiast {@link #doImport}.
     */
    @Async("applicationTaskExecutor")
    public void processJsonImportAsync(UUID jobId, MultipartFile file, DeduplicationMode deduplicationMode,
                                       TenantContext.Snapshot snapshot) {
        TenantContext.restore(snapshot);
        try {
            UUID tenantId = TenantContext.getTenantId();

            log.info("[CustomerImport] Przetwarzanie JSON: jobId={}, tenant={}", jobId, tenantId);

            // Przejście do PROCESSING
            Map<String, Object> state = loadJobState(jobId);
            if (state == null) {
                log.error("[CustomerImport] Brak statusu joba w Redis: jobId={}", jobId);
                return;
            }
            state.put("status", "PROCESSING");
            state.put("startedAt", Instant.now().toString());
            saveJobState(jobId, state);

            doJsonImport(jobId, tenantId, file, deduplicationMode, state);

        } catch (Exception e) {
            log.error("[CustomerImport] Nieoczekiwany błąd importu JSON: jobId={}, error={}", jobId, e.getMessage(), e);
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

    private void doImport(UUID jobId, UUID tenantId, MultipartFile file,
                          DeduplicationMode mode, String columnSeparator, String quoteChar,
                          String columnMappingJson, Map<String, Object> state) throws IOException {

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        int totalRows = 0;
        int rowNumber = 0;
        boolean hasHeader = false;
        ParsedMapping mapping = new ParsedMapping(new HashMap<>(), new HashMap<>(), new LinkedHashMap<>(), null);
        // Czy job ma zmapowane kolumny zgody (consent_given/marketing_consent) – ustalane raz,
        // zaraz po rozstrzygnięciu finalnego mapowania, i używane do wyboru wariantu SQL UPDATE
        // (patrz batchUpdateCustomers) tak, żeby re-import bez kolumn zgód NIE zerował istniejącej zgody.
        boolean hasConsentMapping = false;
        ImportCounters counters = new ImportCounters();

        ParsedMapping explicitMapping = parseColumnMappingJson(columnMappingJson);
        boolean hasExplicitMapping = !explicitMapping.single().isEmpty()
                || !explicitMapping.multi().isEmpty()
                || !explicitMapping.customFields().isEmpty()
                || explicitMapping.legacyCustomFieldsColumn() != null;

        char sep   = resolveSeparator(columnSeparator);
        char quote = resolveQuote(quoteChar);

        // Śledzi external_id już użyte w tym pliku, żeby wykryć duplikat WEWNĄTRZ pliku
        // (niezależnie od kolizji z klientami już istniejącymi w bazie, sprawdzanej niżej).
        Set<String> seenExternalIds = new HashSet<>();

        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {

            CSVParserBuilder parserBuilder = new CSVParserBuilder().withSeparator(sep);
            if (quote != '\0') {
                parserBuilder.withQuoteChar(quote);
            } else {
                parserBuilder.withIgnoreQuotations(true);
            }
            CSVParser parser = parserBuilder.build();
            CSVReader csvReader = new CSVReaderBuilder(reader).withCSVParser(parser).build();

            if (hasExplicitMapping) {
                mapping = explicitMapping;
                hasConsentMapping = hasConsentMapping(mapping);
                log.debug("[CustomerImport] Mapowanie z parametru: {}", mapping);
            }

            String[] line;
            while ((line = csvReader.readNext()) != null) {
                rowNumber++;

                // Auto-detekcja nagłówka w pierwszym wierszu
                if (rowNumber == 1 && !hasExplicitMapping) {
                    if (isHeader(line)) {
                        hasHeader = true;
                        mapping = buildColumnIndex(line);
                        hasConsentMapping = hasConsentMapping(mapping);
                        log.debug("[CustomerImport] Wykryto nagłówek: {}", mapping);
                        continue;
                    }
                    mapping = defaultColumnIndex();
                    hasConsentMapping = hasConsentMapping(mapping);
                }

                totalRows++;
                int dataRow = hasHeader ? rowNumber - 1 : rowNumber;

                CsvRow csvRow = parseRow(line, mapping);

                processRow(dataRow, csvRow, String.join(",", line), tenantId, mode,
                        seenExternalIds, batch, state, counters, CONSENT_SOURCE_CSV_IMPORT);

                // Flush batcha po BATCH_SIZE wierszach
                if (batch.size() >= BATCH_SIZE) {
                    flushBatch(batch, mode, hasConsentMapping, CONSENT_SOURCE_CSV_IMPORT);
                    batch.clear();

                    // Aktualizuj postęp w Redis co chunk
                    state.put("processed", totalRows);
                    state.put("imported",  counters.imported);
                    state.put("updated",   counters.updated);
                    state.put("skipped",   counters.skipped);
                    state.put("failed",    counters.failed);
                    saveJobState(jobId, state);

                    log.debug("[CustomerImport] Chunk: jobId={}, przetworzono={}, zaimportowano={}, zaktualizowano={}",
                            jobId, totalRows, counters.imported, counters.updated);
                }
            }

            // Flush pozostałości
            if (!batch.isEmpty()) {
                flushBatch(batch, mode, hasConsentMapping, CONSENT_SOURCE_CSV_IMPORT);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Błąd parsowania CSV: " + e.getMessage(), e);
        }

        finalizeImportState(jobId, state, totalRows, counters);
    }

    /**
     * Przetwarza pojedynczy wiersz importu (CSV lub JSON) – logika współdzielona przez obie
     * ścieżki: walidacja telefonu, wykrycie duplikatu external_id (w pliku i w bazie), wyszukanie
     * istniejącego klienta, decyzja SKIP/OVERWRITE, dodanie do batcha i inkrementacja liczników.
     *
     * @param dataRow         numer wiersza danych (do raportu błędów) – 1-indeksowany, bez nagłówka
     * @param csvRow          sparsowany wiersz (format neutralny – współdzielony przez CSV i JSON)
     * @param rawDataForError surowa reprezentacja wiersza do raportu błędów (linia CSV lub JSON obiektu)
     * @param tenantId        tenant bieżącego importu
     * @param mode            strategia deduplikacji (SKIP/OVERWRITE)
     * @param seenExternalIds zbiór external_id już użytych w tym pliku (mutowany)
     * @param batch           batch wierszy do wstawienia/aktualizacji (mutowany)
     * @param state           stan joba w Redis – używany do zapisu błędów (appendError)
     * @param counters        liczniki importu (mutowane)
     * @param source          źródło importu ("CSV_IMPORT"/"JSON_IMPORT") – przekazywane dalej do
     *                        budowania gdpr_consent.consent_source (patrz {@link #buildInsertRow}/
     *                        {@link #buildUpdateRow})
     */
    private void processRow(int dataRow, CsvRow csvRow, String rawDataForError,
                            UUID tenantId, DeduplicationMode mode, Set<String> seenExternalIds,
                            List<Object[]> batch, Map<String, Object> state, ImportCounters counters,
                            String source) {

        // Walidacja – wymaga przynajmniej jednego poprawnego telefonu
        List<String> validPhones = filterValidPhones(csvRow.phones());
        if (validPhones.isEmpty()) {
            String rawPhones = csvRow.phones() != null ? String.join(";", csvRow.phones()) : "(empty)";
            String reason = "Brak poprawnego numeru telefonu E.164";
            log.debug("[CustomerImport] Odrzucono wiersz {}: {}, phone='{}'", dataRow, reason, rawPhones);
            appendError(state, dataRow, reason, rawDataForError);
            counters.failed++;
            return;
        }

        // Normalizacja external_id: pusty/blank → null (brak identyfikatora zewnętrznego)
        String externalId = (csvRow.externalId() != null && !csvRow.externalId().isBlank())
                ? csvRow.externalId().trim() : null;

        if (externalId != null && seenExternalIds.contains(externalId)) {
            String reason = "Duplikat identyfikatora zewnętrznego (external_id) w tym samym pliku";
            log.debug("[CustomerImport] Odrzucono wiersz {}: {}, externalId='{}'", dataRow, reason, externalId);
            appendError(state, dataRow, reason, rawDataForError);
            counters.failed++;
            return;
        }

        // Szukaj istniejącego klienta po phone lub email
        Optional<Customer> existing = findExisting(tenantId, validPhones, csvRow.emails());

        if (externalId != null) {
            Optional<Customer> conflicting = customerRepository.findByExternalId(externalId, tenantId);
            boolean conflict = conflicting.isPresent()
                    && (existing.isEmpty() || !conflicting.get().getCustomerId().equals(existing.get().getCustomerId()));
            if (conflict) {
                String reason = "Klient z identyfikatorem zewnętrznym '" + externalId + "' już istnieje";
                log.debug("[CustomerImport] Odrzucono wiersz {}: {}", dataRow, reason);
                appendError(state, dataRow, reason, rawDataForError);
                counters.failed++;
                return;
            }
            seenExternalIds.add(externalId);
        }

        if (existing.isPresent()) {
            if (mode == DeduplicationMode.SKIP) {
                counters.skipped++;
                log.debug("[CustomerImport] SKIP duplikat: wiersz={}", dataRow);
                return;
            }
            // OVERWRITE – wstaw do batcha z flagą aktualizacji
            batch.add(buildUpdateRow(existing.get().getCustomerId(), tenantId, csvRow, validPhones, externalId, source));
            counters.updated++;
        } else {
            batch.add(buildInsertRow(tenantId, csvRow, validPhones, externalId, source));
            counters.imported++;
        }
    }

    /**
     * Finalizuje stan joba po przetworzeniu wszystkich wierszy (CSV lub JSON) – ustala status
     * końcowy (COMPLETED/FAILED_PARTIAL), zapisuje liczniki i, jeśli są błędy, raport błędów CSV.
     */
    @SuppressWarnings("unchecked")
    private void finalizeImportState(UUID jobId, Map<String, Object> state, int totalRows, ImportCounters counters) {
        String finalStatus = counters.failed > 0 ? "FAILED_PARTIAL" : "COMPLETED";
        state.put("status",      finalStatus);
        state.put("total",       totalRows);
        state.put("processed",   totalRows);
        state.put("imported",    counters.imported);
        state.put("updated",     counters.updated);
        state.put("skipped",     counters.skipped);
        state.put("failed",      counters.failed);
        state.put("completedAt", Instant.now().toString());
        state.put("errorFileAvailable", counters.failed > 0);
        saveJobState(jobId, state);

        // Zapisz raport błędów jako CSV w Redis jeśli są błędy
        if (counters.failed > 0) {
            saveErrorReport(jobId, (List<String[]>) state.get("errors"));
        }

        log.info("[CustomerImport] Import zakończony: jobId={}, status={}, total={}, imported={}, updated={}, skipped={}, failed={}",
                jobId, finalStatus, totalRows, counters.imported, counters.updated, counters.skipped, counters.failed);
    }

    // =========================================================================
    // Logika importu JSON
    // =========================================================================

    /**
     * Przetwarza import klientów z pliku JSON (tablica obiektów camelCase).
     *
     * <p>Cały plik jest wczytywany do pamięci jako {@code List<Map<String,Object>>} (limit 50 MB
     * gwarantowany przez {@link #validateJsonFile}), a następnie każdy wiersz przechodzi przez
     * współdzielone {@link #processRow}. Chunking, aktualizacja stanu w Redis i finalizacja
     * statusu są identyczne z importem CSV (patrz {@link #doImport}).
     *
     * @throws IOException gdy root JSON nie jest tablicą lub plik jest niepoprawnym JSON-em –
     *                      propaguje się do {@link #processJsonImportAsync}, kończąc job jako
     *                      {@code FAILED_PARTIAL} z wpisem FATAL (ten sam mechanizm co błąd
     *                      parsowania CSV)
     */
    private void doJsonImport(UUID jobId, UUID tenantId, MultipartFile file,
                              DeduplicationMode mode, Map<String, Object> state) throws IOException {

        List<Map<String, Object>> rows;
        try (var inputStream = file.getInputStream()) {
            JsonNode root = objectMapper.readTree(inputStream);
            if (!root.isArray()) {
                throw new IOException("Plik JSON musi zawierać tablicę obiektów klientów.");
            }
            rows = objectMapper.convertValue(root, new TypeReference<List<Map<String, Object>>>() {});
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Błąd parsowania JSON: " + e.getMessage(), e);
        }

        // Zgoda RODO jest mergowana (nie nadpisywana) tylko gdy CHOĆ JEDEN wiersz w całym pliku
        // ma zagnieżdżony obiekt gdprConsent z consent_given lub marketing_consent – analogicznie
        // do hasConsentMapping w CSV (tam liczone z nagłówka kolumny, tu z zawartości wierszy).
        boolean hasConsentMapping = rows.stream().anyMatch(this::rowHasConsentMapping);

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        Set<String> seenExternalIds = new HashSet<>();
        ImportCounters counters = new ImportCounters();
        int totalRows = rows.size();

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> obj = rows.get(i);
            int dataRow = i + 1;

            CsvRow csvRow = parseJsonRow(obj);
            String rawDataForError = serializeRowForError(obj);

            processRow(dataRow, csvRow, rawDataForError, tenantId, mode,
                    seenExternalIds, batch, state, counters, CONSENT_SOURCE_JSON_IMPORT);

            // Flush batcha po BATCH_SIZE wierszach
            if (batch.size() >= BATCH_SIZE) {
                flushBatch(batch, mode, hasConsentMapping, CONSENT_SOURCE_JSON_IMPORT);
                batch.clear();

                // Aktualizuj postęp w Redis co chunk
                state.put("processed", dataRow);
                state.put("imported",  counters.imported);
                state.put("updated",   counters.updated);
                state.put("skipped",   counters.skipped);
                state.put("failed",    counters.failed);
                saveJobState(jobId, state);

                log.debug("[CustomerImport] Chunk JSON: jobId={}, przetworzono={}, zaimportowano={}, zaktualizowano={}",
                        jobId, dataRow, counters.imported, counters.updated);
            }
        }

        // Flush pozostałości
        if (!batch.isEmpty()) {
            flushBatch(batch, mode, hasConsentMapping, CONSENT_SOURCE_JSON_IMPORT);
        }

        finalizeImportState(jobId, state, totalRows, counters);
    }

    /** Czy wiersz JSON ma zagnieżdżony obiekt gdprConsent z kluczem consent_given lub marketing_consent. */
    private boolean rowHasConsentMapping(Map<String, Object> obj) {
        return obj.get("gdprConsent") instanceof Map<?, ?> consentMap
                && (consentMap.containsKey(JSON_KEY_CONSENT_GIVEN) || consentMap.containsKey(JSON_KEY_MARKETING_CONSENT));
    }

    /**
     * Mapuje jeden obiekt JSON (camelCase) na wewnętrzny neutralny format {@link CsvRow},
     * współdzielony z importem CSV.
     */
    private CsvRow parseJsonRow(Map<String, Object> obj) {
        String firstName  = jsonStringOrNull(obj.get("firstName"));
        String lastName   = jsonStringOrNull(obj.get("lastName"));
        String externalId = jsonStringOrNull(obj.get("externalId"));

        List<String> phones = parseJsonMultiValue(obj.get("phone"));
        List<String> emails = parseJsonMultiValue(obj.get("email"));

        Map<String, String> customFields = new LinkedHashMap<>();
        if (obj.get("customFields") instanceof Map<?, ?> customFieldsMap) {
            for (Map.Entry<?, ?> entry : customFieldsMap.entrySet()) {
                if (entry.getValue() != null) {
                    customFields.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        }

        Boolean consentGiven     = null;
        Boolean marketingConsent = null;
        if (obj.get("gdprConsent") instanceof Map<?, ?> consentMap) {
            consentGiven     = extractJsonBoolean(consentMap.get(JSON_KEY_CONSENT_GIVEN));
            marketingConsent = extractJsonBoolean(consentMap.get(JSON_KEY_MARKETING_CONSENT));
        }

        return new CsvRow(firstName, lastName, phones, emails, customFields, externalId,
                consentGiven, marketingConsent);
    }

    /**
     * Konwertuje wartość pola JSON na String, traktując puste/blank jako brak danych (null).
     * Przycina białe znaki – spójnie z {@link #getColumnByIndex} używanym po stronie CSV.
     */
    private String jsonStringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    /**
     * Parsuje pole phone/email z wiersza JSON – string pojedynczy LUB tablica stringów, oba
     * przypadki przepuszczane przez istniejący {@link #splitMultiValue} (spójność z CSV).
     */
    private List<String> parseJsonMultiValue(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    result.addAll(splitMultiValue(String.valueOf(item)));
                }
            }
        } else if (value != null) {
            result.addAll(splitMultiValue(String.valueOf(value)));
        }
        return result;
    }

    /**
     * Wyciąga wartość boolean z pola gdprConsent JSON: {@code Boolean} wprost, {@code String}
     * przez istniejące {@link #parseBoolean(String)}, inny typ/null → {@code null}.
     */
    private Boolean extractJsonBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return parseBoolean(s);
        }
        return null;
    }

    /** Serializuje wiersz JSON do raportu błędów – fallback na toString() przy błędzie serializacji. */
    private String serializeRowForError(Map<String, Object> obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    // =========================================================================
    // Batch insert / update
    // =========================================================================

    private void flushBatch(List<Object[]> batch, DeduplicationMode mode, boolean hasConsentMapping, String source) {
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
            batchInsertCustomers(inserts, source);
        }
        if (!updates.isEmpty()) {
            batchUpdateCustomers(updates, hasConsentMapping);
        }
    }

    /**
     * Batch INSERT nowych klientów.
     *
     * @param rows   wiersze do wstawienia – patrz {@link #buildInsertRow} po opis indeksów
     * @param source wartość kolumny {@code source} ({@value #CONSENT_SOURCE_CSV_IMPORT} lub
     *               {@value #CONSENT_SOURCE_JSON_IMPORT}) – zależna od formatu importu, bindowana
     *               jako parametr JDBC (nie konkatenacja SQL)
     */
    private void batchInsertCustomers(List<Object[]> rows, String source) {
        String sql = """
                INSERT INTO customer
                    (customer_id, tenant_id, first_name, last_name, phone, email,
                     custom_fields, external_id, gdpr_consent, source, is_deleted, created_at)
                VALUES
                    (gen_random_uuid(), CAST(? AS uuid), ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
                     CAST(? AS jsonb), ?, CAST(? AS jsonb), ?, false, ?)
                ON CONFLICT DO NOTHING
                """;

        // Usuń znacznik customerId=null (row[0]) – nie jest parametrem SQL (customer_id generowany
        // przez gen_random_uuid()); przetasuj pozostałe pola do kolejności placeholderów INSERT.
        // row: [null, tenantId, firstName, lastName, phonesJson, emailsJson, customFieldsJson,
        //       externalId, gdprConsentJson, createdAt]
        List<Object[]> insertParams = new ArrayList<>();
        for (Object[] row : rows) {
            insertParams.add(new Object[]{
                    row[1], // tenant_id
                    row[2], // first_name
                    row[3], // last_name
                    row[4], // phone (jsonb)
                    row[5], // email (jsonb)
                    row[6], // custom_fields (jsonb)
                    row[7], // external_id
                    row[8], // gdpr_consent (jsonb)
                    source, // source
                    row[9]  // created_at
            });
        }

        jdbcTemplate.batchUpdate(sql, insertParams);
        log.debug("[CustomerImport] Batch INSERT: {} klientów (source={})", rows.size(), source);
    }

    private static final String UPDATE_SQL_WITHOUT_CONSENT = """
            UPDATE customer
            SET first_name    = ?,
                last_name     = ?,
                phone         = CAST(? AS jsonb),
                email         = CAST(? AS jsonb),
                custom_fields = CAST(? AS jsonb),
                external_id   = ?,
                updated_at    = ?
            WHERE customer_id = CAST(? AS uuid)
              AND tenant_id   = CAST(? AS uuid)
              AND is_deleted  = false
            """;

    // Merge (||), NIE pełne nadpisanie – zachowuje istniejące klucze gdpr_consent niewymienione
    // w nowej mapie (np. gdy import mapuje tylko consent_given, marketing_consent pozostaje bez zmian).
    private static final String UPDATE_SQL_WITH_CONSENT = """
            UPDATE customer
            SET first_name    = ?,
                last_name     = ?,
                phone         = CAST(? AS jsonb),
                email         = CAST(? AS jsonb),
                custom_fields = CAST(? AS jsonb),
                external_id   = ?,
                gdpr_consent  = gdpr_consent || CAST(? AS jsonb),
                updated_at    = ?
            WHERE customer_id = CAST(? AS uuid)
              AND tenant_id   = CAST(? AS uuid)
              AND is_deleted  = false
            """;

    /**
     * Batch UPDATE klientów (tryb OVERWRITE).
     *
     * <p><b>Krytyczne dla ochrony danych RODO:</b> {@code gdpr_consent} jest aktualizowane
     * TYLKO gdy {@code hasConsentMapping=true} (job ma jawnie zmapowane kolumny consent_given
     * lub marketing_consent). W przeciwnym razie re-import samego telefonu/custom_fields NIE
     * dotyka istniejącej zgody klienta – zapobiega to cichemu zerowaniu zgód.
     *
     * @param rows              wiersze do aktualizacji – patrz {@link #buildUpdateRow} po opis indeksów
     * @param hasConsentMapping czy job ma zmapowane kolumny zgody (ustalone raz na cały import)
     */
    private void batchUpdateCustomers(List<Object[]> rows, boolean hasConsentMapping) {
        String sql = hasConsentMapping ? UPDATE_SQL_WITH_CONSENT : UPDATE_SQL_WITHOUT_CONSENT;

        // row: [customerId, tenantId, firstName, lastName, phonesJson, emailsJson, customFieldsJson,
        //       externalId, gdprConsentJson, updatedAt]
        List<Object[]> updateParams = new ArrayList<>();
        for (Object[] row : rows) {
            if (hasConsentMapping) {
                updateParams.add(new Object[]{
                        row[2], // first_name
                        row[3], // last_name
                        row[4], // phone (jsonb)
                        row[5], // email (jsonb)
                        row[6], // custom_fields (jsonb)
                        row[7], // external_id
                        row[8], // gdpr_consent (merge jsonb)
                        row[9], // updated_at
                        row[0], // customer_id (WHERE)
                        row[1]  // tenant_id (WHERE)
                });
            } else {
                updateParams.add(new Object[]{
                        row[2], // first_name
                        row[3], // last_name
                        row[4], // phone (jsonb)
                        row[5], // email (jsonb)
                        row[6], // custom_fields (jsonb)
                        row[7], // external_id
                        row[9], // updated_at
                        row[0], // customer_id (WHERE)
                        row[1]  // tenant_id (WHERE)
                });
            }
        }

        jdbcTemplate.batchUpdate(sql, updateParams);
        log.debug("[CustomerImport] Batch UPDATE: {} klientów (hasConsentMapping={})", rows.size(), hasConsentMapping);
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
     * Kolejność: [null, tenantId, firstName, lastName, phonesJson, emailsJson, customFieldsJson,
     *             externalId, gdprConsentJson, createdAt]
     *
     * <p>Nowy klient zawsze dostaje jawny klucz {@code consent_given} w {@code gdpr_consent}: baza
     * startuje od {@code {"consent_given": false}} (spójnie z
     * {@code CustomerServiceImpl.defaultGdprConsent()} dla klientów tworzonych ręcznie), a następnie
     * nakładane są na nią realne wartości z {@link #buildGdprConsent(Boolean, Boolean)}. Dzięki temu
     * klucz {@code consent_given} jest zawsze obecny – domyślnie {@code false}, nadpisany wartością
     * z CSV gdy ta kolumna była zmapowana – nawet jeśli CSV dostarczył tylko
     * {@code marketing_consent} bez {@code consent_given}.
     *
     * @param externalId znormalizowany (przycięty, null gdy pusty) identyfikator zewnętrzny –
     *                   ustalony i zweryfikowany pod kątem kolizji przed wywołaniem tej metody
     * @param source     źródło zgody RODO ({@code gdpr_consent.consent_source}) – "CSV_IMPORT"
     *                   lub "JSON_IMPORT" w zależności od formatu importu
     */
    private Object[] buildInsertRow(UUID tenantId, CsvRow csvRow, List<String> validPhones, String externalId, String source) {
        List<String> allEmails = csvRow.emails() != null ? csvRow.emails() : List.of();
        String gdprConsentJson = toJson(buildInsertGdprConsent(csvRow.consentGiven(), csvRow.marketingConsent(), source));
        return new Object[]{
                null,                          // [0] customerId = null → INSERT
                tenantId.toString(),           // [1]
                csvRow.firstName(),            // [2]
                csvRow.lastName(),             // [3]
                toJsonArray(validPhones),      // [4] phone (JSONB)
                toJsonArray(allEmails),        // [5] email (JSONB)
                toJson(csvRow.customFields()), // [6] custom_fields
                externalId,                    // [7] external_id (znormalizowany)
                gdprConsentJson,               // [8] gdpr_consent (JSONB)
                Timestamp.from(Instant.now())  // [9] created_at
        };
    }

    /**
     * Buduje wiersz dla UPDATE.
     * Kolejność: [customerId, tenantId, firstName, lastName, phonesJson, emailsJson, customFieldsJson,
     *             externalId, gdprConsentJson, updatedAt]
     *
     * <p>Pole {@code gdprConsentJson} (indeks 8) jest budowane zawsze, ale wykorzystywane przez
     * {@link #batchUpdateCustomers} TYLKO gdy job ma zmapowane kolumny zgody (parametr
     * {@code hasConsentMapping}) – pusta mapa {@code {}} oznacza wtedy no-op merge JSONB.
     *
     * @param externalId znormalizowany (przycięty, null gdy pusty) identyfikator zewnętrzny –
     *                   ustalony i zweryfikowany pod kątem kolizji przed wywołaniem tej metody
     * @param source     źródło zgody RODO ({@code gdpr_consent.consent_source}) – "CSV_IMPORT"
     *                   lub "JSON_IMPORT" w zależności od formatu importu
     */
    private Object[] buildUpdateRow(UUID customerId, UUID tenantId, CsvRow csvRow, List<String> validPhones, String externalId, String source) {
        List<String> allEmails = csvRow.emails() != null ? csvRow.emails() : List.of();
        Map<String, Object> gdprConsent = buildGdprConsent(csvRow.consentGiven(), csvRow.marketingConsent(), source);
        return new Object[]{
                customerId.toString(),         // [0] customerId → UPDATE
                tenantId.toString(),           // [1]
                csvRow.firstName(),            // [2]
                csvRow.lastName(),             // [3]
                toJsonArray(validPhones),      // [4] phone (JSONB)
                toJsonArray(allEmails),        // [5] email (JSONB)
                toJson(csvRow.customFields()), // [6] custom_fields
                externalId,                    // [7] external_id (znormalizowany)
                toJson(gdprConsent),           // [8] gdpr_consent (JSONB merge – używane warunkowo)
                Timestamp.from(Instant.now())  // [9] updated_at
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

    /**
     * Waliduje plik JSON: rozszerzenie i rozmiar (analogicznie do {@link #validateFile}).
     *
     * @throws IllegalArgumentException gdy plik nie spełnia wymagań
     */
    public void validateJsonFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Plik JSON jest wymagany i nie może być pusty.");
        }

        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase().endsWith(".json")) {
            throw new IllegalArgumentException(
                    "Dozwolone są tylko pliki JSON (.json). Przesłany plik: " + name);
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    String.format("Plik JSON jest za duży: %d MB. Maksymalny dozwolony rozmiar: 50 MB.",
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

    /**
     * Auto-detekcja mapowania kolumn z wiersza nagłówka CSV.
     *
     * <p>Nagłówek {@code phone}/{@code email} zawsze trafia do {@link ParsedMapping#multi()}
     * (jednoelementowa lista, chyba że nagłówek powtarza się – wtedy indeksy się kumulują).
     * Literalny nagłówek {@code custom_fields} → {@link ParsedMapping#legacyCustomFieldsColumn()}
     * (zachowanie jak dotychczas: parsowanie zawartości komórki jako JSON). Nagłówki zaczynające
     * się na {@code custom_} (inne niż dokładnie {@code custom_fields}) → {@link ParsedMapping#customFields()}
     * z kluczem = pełna nazwa nagłówka.
     */
    private ParsedMapping buildColumnIndex(String[] header) {
        Map<String, Integer> single = new HashMap<>();
        Map<String, List<Integer>> multi = new HashMap<>();
        Map<String, Integer> customFields = new LinkedHashMap<>();
        Integer legacyCustomFieldsColumn = null;

        for (int i = 0; i < header.length; i++) {
            String name = header[i].trim().toLowerCase();

            if (COL_CUSTOM.equals(name)) {
                legacyCustomFieldsColumn = i;
            } else if (name.startsWith("custom_")) {
                customFields.put(name, i);
            } else if (COL_PHONE.equals(name) || COL_EMAIL.equals(name)) {
                multi.computeIfAbsent(name, k -> new ArrayList<>()).add(i);
            } else {
                single.put(name, i);
            }
        }

        return new ParsedMapping(single, multi, customFields, legacyCustomFieldsColumn);
    }

    /**
     * Pozycyjne mapowanie domyślne – gdy brak nagłówka i brak jawnego mapowania z frontendu.
     * Celowo NIE zawiera domyślnych pozycji dla consent_given/marketing_consent (zgoda RODO
     * importowana jest wyłącznie gdy jawnie zmapowana – jawność ochrania przed przypadkowym
     * odczytaniem niewłaściwej kolumny jako zgody).
     */
    private ParsedMapping defaultColumnIndex() {
        Map<String, Integer> single = new HashMap<>();
        single.put(COL_FIRST_NAME,  0);
        single.put(COL_LAST_NAME,   1);
        single.put(COL_EXTERNAL_ID, 4);

        Map<String, List<Integer>> multi = new HashMap<>();
        multi.put(COL_PHONE, new ArrayList<>(List.of(2)));
        multi.put(COL_EMAIL, new ArrayList<>(List.of(3)));

        return new ParsedMapping(single, multi, new LinkedHashMap<>(), null);
    }

    /** Czy mapowanie zawiera jawnie zmapowaną kolumnę consent_given lub marketing_consent. */
    private boolean hasConsentMapping(ParsedMapping mapping) {
        return mapping.single().containsKey(COL_CONSENT_GIVEN)
                || mapping.single().containsKey(COL_MARKETING_CONSENT);
    }

    private CsvRow parseRow(String[] line, ParsedMapping mapping) {
        String firstName  = getColumn(line, mapping.single(), COL_FIRST_NAME);
        String lastName   = getColumn(line, mapping.single(), COL_LAST_NAME);
        String externalId = getColumn(line, mapping.single(), COL_EXTERNAL_ID);

        List<String> phones = getMultiColumn(line, mapping.multi().get(COL_PHONE));
        List<String> emails = getMultiColumn(line, mapping.multi().get(COL_EMAIL));

        Map<String, String> customFields = parseCustomFields(mapping, line);

        Boolean consentGiven      = parseBoolean(getColumn(line, mapping.single(), COL_CONSENT_GIVEN));
        Boolean marketingConsent  = parseBoolean(getColumn(line, mapping.single(), COL_MARKETING_CONSENT));

        return new CsvRow(firstName, lastName, phones, emails, customFields, externalId,
                consentGiven, marketingConsent);
    }

    /**
     * Parsuje custom_fields.
     *
     * <p>Priorytet (zgodny z dotychczasowym zachowaniem, rozszerzony o nazwane pola):
     * <ol>
     *   <li>Jeśli jest zmapowana pojedyncza "legacy" kolumna custom_fields (literalny nagłówek
     *       lub jawne {@code {"custom_fields": <indeks>}}) i komórka w tym wierszu jest niepusta –
     *       parsuje jej zawartość jako JSON (fallback: literalna wartość pod kluczem
     *       {@value #COL_CUSTOM}, gdy zawartość nie jest poprawnym JSON-em).</li>
     *   <li>W przeciwnym razie – dla każdego wpisu w {@link ParsedMapping#customFields()}
     *       (nazwane pole → indeks kolumny, czy to z jawnego mapowania {@code {"custom_fields": {...}}}
     *       czy z auto-detekcji nagłówków {@code custom_*}) odczytuje wartość komórki wprost.</li>
     * </ol>
     */
    private Map<String, String> parseCustomFields(ParsedMapping mapping, String[] line) {
        Map<String, String> result = new LinkedHashMap<>();

        if (mapping.legacyCustomFieldsColumn() != null) {
            String customRaw = getColumnByIndex(line, mapping.legacyCustomFieldsColumn());
            if (customRaw != null && !customRaw.isBlank()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsed = objectMapper.readValue(customRaw, Map.class);
                    parsed.forEach((k, v) -> {
                        if (v != null) result.put(k, v.toString());
                    });
                    return result;
                } catch (Exception ignored) {
                    // Nie jest JSON – traktuj jako zwykłą wartość
                    result.put(COL_CUSTOM, customRaw);
                    return result;
                }
            }
        }

        for (Map.Entry<String, Integer> entry : mapping.customFields().entrySet()) {
            String val = getColumnByIndex(line, entry.getValue());
            if (val != null && !val.isBlank()) {
                result.put(entry.getKey(), val);
            }
        }

        return result;
    }

    private String getColumn(String[] line, Map<String, Integer> idx, String colName) {
        return getColumnByIndex(line, idx.get(colName));
    }

    private String getColumnByIndex(String[] line, Integer index) {
        if (index == null || index < 0 || index >= line.length) {
            return null;
        }
        String val = line[index].trim();
        return val.isEmpty() ? null : val;
    }

    /**
     * Odczytuje i łączy wartości z wielu kolumn CSV (np. kilka kolumn zmapowanych na "phone").
     * Każda niepusta komórka jest dodatkowo dzielona po {@value #MULTI_VALUE_SEPARATOR} (wartości
     * wielokrotne w jednej komórce), a wynik jest spłaszczoną listą wszystkich wartości ze
     * wszystkich wskazanych kolumn (puste fragmenty pomijane).
     *
     * @param indices indeksy kolumn do odczytu; {@code null} → pusta lista
     */
    private List<String> getMultiColumn(String[] line, List<Integer> indices) {
        if (indices == null) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (Integer index : indices) {
            if (index == null || index < 0 || index >= line.length) {
                continue;
            }
            result.addAll(splitMultiValue(line[index]));
        }
        return result;
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
     * Parsuje wartość zgody (tak/nie) z komórki CSV. Zgoda jest polem opcjonalnym – nierozpoznana
     * wartość jest logowana na poziomie debug i traktowana jako brak danych (null), NIE jako
     * twardy błąd blokujący import wiersza.
     *
     * @return {@code true}/{@code false} dla rozpoznanej wartości, {@code null} gdy puste lub nierozpoznane
     */
    private Boolean parseBoolean(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase();
        return switch (normalized) {
            case "tak", "true", "1", "yes" -> true;
            case "nie", "false", "0", "no" -> false;
            default -> {
                log.debug("[CustomerImport] Nierozpoznana wartość zgody: '{}' – traktowana jako brak danych", raw);
                yield null;
            }
        };
    }

    /**
     * Buduje mapę {@code gdpr_consent} z wartości zgody wiersza (CSV lub JSON).
     *
     * <p>Zawiera tylko klucze, dla których wartość jest niepusta. Gdy przynajmniej jedna z dwóch
     * wartości jest niepusta, dodaje też {@code consent_source} (parametr {@code source}) i
     * {@code consent_date} (znacznik czasu importu). Gdy obie wartości są {@code null} – zwraca
     * pustą mapę (nie null).
     *
     * @param source wartość {@code consent_source} – {@value #CONSENT_SOURCE_CSV_IMPORT} lub
     *               {@value #CONSENT_SOURCE_JSON_IMPORT}
     */
    private Map<String, Object> buildGdprConsent(Boolean consentGiven, Boolean marketingConsent, String source) {
        Map<String, Object> consent = new LinkedHashMap<>();
        if (consentGiven != null) {
            consent.put(COL_CONSENT_GIVEN, consentGiven);
        }
        if (marketingConsent != null) {
            consent.put(COL_MARKETING_CONSENT, marketingConsent);
        }
        if (!consent.isEmpty()) {
            consent.put("consent_source", source);
            consent.put("consent_date", Instant.now().toString());
        }
        return consent;
    }

    /**
     * Buduje {@code gdpr_consent} dla NOWEGO klienta (ścieżka INSERT).
     *
     * <p>W przeciwieństwie do {@link #buildGdprConsent(Boolean, Boolean, String)} (używanego wprost
     * przez UPDATE, gdzie pusta/częściowa mapa oznacza poprawny no-op merge JSONB i NIE wolno jej
     * uzupełniać domyślnymi wartościami – patrz {@link #buildUpdateRow}), tutaj wynik zawsze
     * zawiera jawny klucz {@code consent_given}: baza {@code {"consent_given": false}} jest
     * nakładana realnymi wartościami z importu, więc brak zmapowanej kolumny {@code consent_given}
     * (nawet przy obecności samego {@code marketing_consent}) nie usuwa tego klucza z wyniku –
     * spójnie z {@code CustomerServiceImpl.defaultGdprConsent()}.
     */
    private Map<String, Object> buildInsertGdprConsent(Boolean consentGiven, Boolean marketingConsent, String source) {
        Map<String, Object> consent = new LinkedHashMap<>();
        consent.put(COL_CONSENT_GIVEN, false);
        consent.putAll(buildGdprConsent(consentGiven, marketingConsent, source));
        return consent;
    }

    /**
     * Parsuje JSON mapowania kolumn z frontendu.
     *
     * <p>Format wejściowy:
     * <pre>{@code
     * {
     *   "first_name": 0,
     *   "phone": [2, 5],           // wiele kolumn CSV → jedna lista wartości (liczba pojedyncza nadal akceptowana)
     *   "email": [3],
     *   "consent_given": 6,
     *   "marketing_consent": 7,
     *   "custom_fields": {"vip": 8, "segment": 9}   // nazwane pola dodatkowe (liczba pojedyncza nadal akceptowana – legacy)
     * }
     * }</pre>
     *
     * @param json JSON string lub null/pusty
     * @return {@link ParsedMapping}; wszystkie pola puste/null gdy json jest null/pusty/błędny
     */
    @SuppressWarnings("unchecked")
    public ParsedMapping parseColumnMappingJson(String json) {
        Map<String, Integer> single = new HashMap<>();
        Map<String, List<Integer>> multi = new HashMap<>();
        Map<String, Integer> customFields = new LinkedHashMap<>();
        Integer legacyCustomFieldsColumn = null;

        if (json == null || json.isBlank()) {
            return new ParsedMapping(single, multi, customFields, null);
        }

        try {
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                if (COL_CUSTOM.equals(key)) {
                    if (value instanceof Map<?, ?> nestedMap) {
                        for (Map.Entry<?, ?> nested : nestedMap.entrySet()) {
                            if (nested.getValue() instanceof Number num) {
                                customFields.put(String.valueOf(nested.getKey()), num.intValue());
                            }
                        }
                    } else if (value instanceof Number num) {
                        legacyCustomFieldsColumn = num.intValue();
                    }
                } else if (COL_PHONE.equals(key) || COL_EMAIL.equals(key)) {
                    if (value instanceof List<?> list) {
                        List<Integer> indices = new ArrayList<>();
                        for (Object item : list) {
                            if (item instanceof Number num) {
                                indices.add(num.intValue());
                            }
                        }
                        multi.put(key, indices);
                    } else if (value instanceof Number num) {
                        multi.put(key, new ArrayList<>(List.of(num.intValue())));
                    }
                } else if (value instanceof Number num) {
                    single.put(key, num.intValue());
                }
            }
        } catch (Exception e) {
            log.warn("[CustomerImport] Błąd parsowania columnMapping JSON: {}", e.getMessage());
            return new ParsedMapping(new HashMap<>(), new HashMap<>(), new LinkedHashMap<>(), null);
        }

        return new ParsedMapping(single, multi, customFields, legacyCustomFieldsColumn);
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

    private String toJson(Map<String, ?> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("[CustomerImport] Błąd serializacji mapy do JSON: {}", e.getMessage());
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
    // Wewnętrzne rekordy – model wiersza CSV i wynik parsowania mapowania kolumn
    // =========================================================================

    /**
     * Liczniki importu mutowane przez {@link #processRow} – zastępują lokalne zmienne {@code int}
     * używane niegdyś wewnątrz {@link #doImport}. Wydzielone do osobnej klasy, ponieważ
     * {@link #processRow} jest wywoływana z dwóch niezależnych pętli (CSV w {@link #doImport},
     * JSON w {@link #doJsonImport}) i musi mutować wspólny stan przekazywany przez referencję.
     */
    private static class ImportCounters {
        int imported;
        int updated;
        int skipped;
        int failed;
    }

    private record CsvRow(
            String firstName,
            String lastName,
            List<String> phones,
            List<String> emails,
            Map<String, String> customFields,
            String externalId,
            Boolean consentGiven,
            Boolean marketingConsent
    ) {}

    /**
     * Wynik parsowania mapowania kolumn CSV → pola systemu, niezależny od źródła (jawne mapowanie
     * JSON z frontendu vs. auto-detekcja nagłówka pliku).
     *
     * @param single                     pola skalarne z pojedynczym indeksem kolumny (first_name,
     *                                   last_name, external_id, consent_given, marketing_consent)
     * @param multi                      pola wielokolumnowe (phone, email) → lista indeksów kolumn
     *                                   łączonych w jedną listę wartości
     * @param customFields               nazwane pola dodatkowe → indeks kolumny (z jawnego mapowania
     *                                   {@code {"custom_fields": {nazwa: indeks}}} lub auto-detekcji
     *                                   nagłówków {@code custom_*})
     * @param legacyCustomFieldsColumn   indeks JEDNEJ kolumny zawierającej surowy JSON custom_fields –
     *                                   wsteczna zgodność z dotychczasowym formatem (literalny nagłówek
     *                                   "custom_fields" lub jawne {@code {"custom_fields": <indeks>}})
     */
    record ParsedMapping(
            Map<String, Integer> single,
            Map<String, List<Integer>> multi,
            Map<String, Integer> customFields,
            Integer legacyCustomFieldsColumn
    ) {}
}
