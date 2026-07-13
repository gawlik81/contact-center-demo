package com.contactcenter.domain.campaign;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.campaign.dto.CampaignContactResponse;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Implementacja {@link CampaignImportService}.
 *
 * <p>Przepływ CSV:
 * <ol>
 *   <li>{@link #initiateImport} – waliduje plik, tworzy rekord statusu w Redis (QUEUED),
 *       pobiera snapshot TenantContext, uruchamia {@link #processImportAsync} w tle.</li>
 *   <li>{@link #processImportAsync} – parsuje CSV, waliduje telefony, batch-insertuje
 *       do {@code campaign_contact} (chunk po {@value #BATCH_SIZE} rekordów),
 *       aktualizuje status co chunk. Na końcu: COMPLETED lub FAILED_PARTIAL.</li>
 * </ol>
 *
 * <p>Przepływ JSON (alternatywa dla CSV, ten sam mechanizm statusu/pollingu):
 * <ol>
 *   <li>{@link #initiateJsonImport} – analogicznie do {@link #initiateImport}, ale waliduje
 *       rozszerzenie {@code .json} i uruchamia {@link #processJsonImportAsync}.</li>
 *   <li>{@link #processJsonImportAsync} – wczytuje cały plik jako tablicę obiektów JSON
 *       (camelCase, zgodne z {@code CampaignContactResponse}), reszta logiki (walidacja telefonu,
 *       batch insert, aktualizacja statusu) współdzielona z CSV przez {@link #processRow},
 *       {@link #flushBatch} i {@link #finalizeJobStatus}.</li>
 * </ol>
 *
 * <p>Status joba przechowywany w Redis pod kluczem {@value #JOB_KEY_PREFIX}{jobId}
 * z TTL {@value #JOB_TTL_SECONDS} sekund (1h).
 */
@Slf4j
@Service
@RequiredArgsConstructor
class CampaignImportServiceImpl implements CampaignImportService {

    // =========================================================================
    // Stałe
    // =========================================================================

    /** Maksymalny rozmiar pliku CSV: 50 MB. */
    static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;

    /** Rozmiar chunka batch insertu – 1000 rekordów na transakcję. */
    static final int BATCH_SIZE = 1000;

    /** Prefiks klucza Redis dla statusu joba. */
    static final String JOB_KEY_PREFIX = "import:job:";

    /** TTL statusu joba w Redis: 1 godzina. */
    public static final long JOB_TTL_SECONDS = 3600L;

    /** Wzorzec E.164: + i 1-15 cyfr. */
    private static final Pattern E164_PATTERN = Pattern.compile("^\\+[1-9]\\d{0,14}$");

    /** Nazwa kolumny telefonu w nagłówku CSV (case-insensitive). */
    private static final String COL_PHONE = "phone";
    private static final String COL_FIRST_NAME = "first_name";
    private static final String COL_LAST_NAME = "last_name";

    // =========================================================================
    // Zależności
    // =========================================================================

    private final CampaignRepository campaignRepository;
    private final CampaignContactRepository campaignContactRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    // =========================================================================
    // Inicjowanie importu (wywołanie synchroniczne z kontrolera)
    // =========================================================================

    /**
     * Waliduje plik i inicjuje asynchroniczny import.
     *
     * <p>Wywołanie synchroniczne – szybkie (walidacja + zapis do Redis + start @Async).
     * Zwraca jobId natychmiast, bez czekania na zakończenie importu.
     *
     * @param campaignId     UUID kampanii (musi należeć do tenanta z TenantContext)
     * @param file           plik CSV (multipart)
     * @param skipDuplicates true = pomiń duplikaty po telefonie (domyślnie true)
     * @return UUID jobu – do pollingu statusu
     * @throws IllegalArgumentException  gdy plik jest za duży lub ma złe rozszerzenie
     * @throws EntityNotFoundException   gdy kampania nie istnieje lub należy do innego tenanta
     */
    @Override
    public UUID initiateImport(UUID campaignId, MultipartFile file, boolean skipDuplicates,
                               String columnSeparator, String quoteChar, String columnMappingJson) {
        UUID tenantId = TenantContext.getTenantId();

        // Walidacja pliku
        validateFile(file);

        // Walidacja kampanii (cross-tenant check)
        requireCampaignInTenant(campaignId, tenantId);

        // Generujemy jobId i tworzymy status joba w Redis (QUEUED)
        UUID jobId = UUID.randomUUID();
        ImportJobStatus jobStatus = buildQueuedStatus(jobId, campaignId);

        saveJobStatus(jobId, jobStatus);

        log.info("[CampaignImport] Import zainicjowany: jobId={}, campaignId={}, tenant={}, " +
                "skipDuplicates={}, separator='{}', quoteChar='{}'",
                jobId, campaignId, tenantId, skipDuplicates, columnSeparator, quoteChar);

        // Snapshot kontekstu przed uruchomieniem wątku async
        TenantContext.Snapshot snapshot = TenantContext.snapshot();

        // Uruchamiamy przetwarzanie w tle (zwraca natychmiast)
        processImportAsync(jobId, campaignId, file, skipDuplicates, columnSeparator, quoteChar, columnMappingJson, snapshot);

        return jobId;
    }

    /**
     * Waliduje plik JSON i inicjuje asynchroniczny import.
     *
     * <p>Mirror {@link #initiateImport} – ten sam mechanizm jobId/Redis/TenantContext snapshot,
     * różni się jedynie walidacją pliku ({@link #validateJsonFile} zamiast {@link #validateFile})
     * i uruchomieniem {@link #processJsonImportAsync} zamiast {@link #processImportAsync}.
     *
     * @param campaignId     UUID kampanii (musi należeć do tenanta z TenantContext)
     * @param file           plik JSON (multipart) – tablica obiektów kontaktów
     * @param skipDuplicates true = pomiń duplikaty po telefonie (domyślnie true)
     * @return UUID jobu – do pollingu statusu (ten sam endpoint co dla CSV)
     * @throws IllegalArgumentException  gdy plik jest za duży lub ma złe rozszerzenie
     * @throws EntityNotFoundException   gdy kampania nie istnieje lub należy do innego tenanta
     */
    @Override
    public UUID initiateJsonImport(UUID campaignId, MultipartFile file, boolean skipDuplicates) {
        UUID tenantId = TenantContext.getTenantId();

        // Walidacja pliku
        validateJsonFile(file);

        // Walidacja kampanii (cross-tenant check)
        requireCampaignInTenant(campaignId, tenantId);

        // Generujemy jobId i tworzymy status joba w Redis (QUEUED)
        UUID jobId = UUID.randomUUID();
        ImportJobStatus jobStatus = buildQueuedStatus(jobId, campaignId);

        saveJobStatus(jobId, jobStatus);

        log.info("[CampaignImport] Import JSON zainicjowany: jobId={}, campaignId={}, tenant={}, skipDuplicates={}",
                jobId, campaignId, tenantId, skipDuplicates);

        // Snapshot kontekstu przed uruchomieniem wątku async
        TenantContext.Snapshot snapshot = TenantContext.snapshot();

        // Uruchamiamy przetwarzanie w tle (zwraca natychmiast)
        processJsonImportAsync(jobId, campaignId, file, skipDuplicates, snapshot);

        return jobId;
    }

    // =========================================================================
    // Przetwarzanie asynchroniczne
    // =========================================================================

    /**
     * Asynchroniczne przetwarzanie importu CSV.
     *
     * <p>Metoda uruchamiana w puli wątków cc-async-* (konfiguracja AsyncConfig).
     * Propagacja TenantContext przez snapshot/restore.
     *
     * @param jobId          UUID jobu (do aktualizacji statusu w Redis)
     * @param campaignId     UUID kampanii
     * @param file           plik CSV do przetworzenia
     * @param skipDuplicates strategia deduplikacji
     * @param snapshot       snapshot TenantContext z wątku HTTP
     */
    @Async("applicationTaskExecutor")
    public void processImportAsync(
            UUID jobId,
            UUID campaignId,
            MultipartFile file,
            boolean skipDuplicates,
            String columnSeparator,
            String quoteChar,
            String columnMappingJson,
            TenantContext.Snapshot snapshot) {

        // Przywracamy kontekst tenanta w wątku async
        TenantContext.restore(snapshot);

        try {
            UUID tenantId = TenantContext.getTenantId();

            log.info("[CampaignImport] Przetwarzanie importu: jobId={}, campaignId={}, tenant={}",
                    jobId, campaignId, tenantId);

            // Przejście do PROCESSING
            ImportJobStatus jobStatus = loadJobStatus(jobId);
            if (jobStatus == null) {
                log.error("[CampaignImport] Brak statusu joba w Redis: jobId={}", jobId);
                return;
            }
            jobStatus.setStatus(ImportJobStatus.Status.PROCESSING);
            jobStatus.setStartedAt(Instant.now());
            saveJobStatus(jobId, jobStatus);

            // Parsowanie CSV i import
            doImport(jobId, campaignId, tenantId, file, skipDuplicates, columnSeparator, quoteChar, columnMappingJson, jobStatus);

        } catch (Exception e) {
            log.error("[CampaignImport] Nieoczekiwany błąd importu: jobId={}, error={}",
                    jobId, e.getMessage(), e);

            // Zapisujemy błąd w statusie
            try {
                ImportJobStatus failedStatus = loadJobStatus(jobId);
                if (failedStatus != null) {
                    failedStatus.setStatus(ImportJobStatus.Status.FAILED_PARTIAL);
                    failedStatus.setCompletedAt(Instant.now());
                    failedStatus.addRejectedSample("FATAL: " + e.getMessage());
                    saveJobStatus(jobId, failedStatus);
                }
            } catch (Exception ex) {
                log.error("[CampaignImport] Nie można zapisać błędu statusu joba: jobId={}", jobId, ex);
            }
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Asynchroniczne przetwarzanie importu JSON.
     *
     * <p>Mirror {@link #processImportAsync} – tożsamy przepływ statusów (QUEUED → PROCESSING →
     * COMPLETED/FAILED_PARTIAL) i identyczna obsługa błędów (catch-all → FAILED_PARTIAL z próbką
     * {@code "FATAL: " + e.getMessage()}), różni się jedynie wywołaniem {@link #doJsonImport}
     * zamiast {@link #doImport}.
     *
     * @param jobId          UUID jobu (do aktualizacji statusu w Redis)
     * @param campaignId     UUID kampanii
     * @param file           plik JSON do przetworzenia
     * @param skipDuplicates strategia deduplikacji
     * @param snapshot       snapshot TenantContext z wątku HTTP
     */
    @Async("applicationTaskExecutor")
    public void processJsonImportAsync(
            UUID jobId,
            UUID campaignId,
            MultipartFile file,
            boolean skipDuplicates,
            TenantContext.Snapshot snapshot) {

        // Przywracamy kontekst tenanta w wątku async
        TenantContext.restore(snapshot);

        try {
            UUID tenantId = TenantContext.getTenantId();

            log.info("[CampaignImport] Przetwarzanie importu JSON: jobId={}, campaignId={}, tenant={}",
                    jobId, campaignId, tenantId);

            // Przejście do PROCESSING
            ImportJobStatus jobStatus = loadJobStatus(jobId);
            if (jobStatus == null) {
                log.error("[CampaignImport] Brak statusu joba w Redis: jobId={}", jobId);
                return;
            }
            jobStatus.setStatus(ImportJobStatus.Status.PROCESSING);
            jobStatus.setStartedAt(Instant.now());
            saveJobStatus(jobId, jobStatus);

            // Parsowanie JSON i import
            doJsonImport(jobId, campaignId, tenantId, file, skipDuplicates, jobStatus);

        } catch (Exception e) {
            log.error("[CampaignImport] Nieoczekiwany błąd importu JSON: jobId={}, error={}",
                    jobId, e.getMessage(), e);

            // Zapisujemy błąd w statusie
            try {
                ImportJobStatus failedStatus = loadJobStatus(jobId);
                if (failedStatus != null) {
                    failedStatus.setStatus(ImportJobStatus.Status.FAILED_PARTIAL);
                    failedStatus.setCompletedAt(Instant.now());
                    failedStatus.addRejectedSample("FATAL: " + e.getMessage());
                    saveJobStatus(jobId, failedStatus);
                }
            } catch (Exception ex) {
                log.error("[CampaignImport] Nie można zapisać błędu statusu joba: jobId={}", jobId, ex);
            }
        } finally {
            TenantContext.clear();
        }
    }

    // =========================================================================
    // Logika importu
    // =========================================================================

    /**
     * Główna logika parsowania CSV i batch insertu.
     *
     * <p>Wiersze parsowane są strumieniowo – OpenCSV czyta je po kolei,
     * co minimalizuje użycie pamięci dla dużych plików.
     *
     * @param jobId          UUID joba (do aktualizacji postępu)
     * @param campaignId     UUID kampanii
     * @param tenantId       UUID tenanta
     * @param file           plik CSV
     * @param skipDuplicates strategia deduplikacji
     * @param jobStatus      obiekt statusu (mutowany w trakcie przetwarzania)
     */
    private void doImport(
            UUID jobId,
            UUID campaignId,
            UUID tenantId,
            MultipartFile file,
            boolean skipDuplicates,
            String columnSeparator,
            String quoteChar,
            String columnMappingJson,
            ImportJobStatus jobStatus) throws IOException, CsvException {

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        AtomicInteger totalRows = new AtomicInteger(0);
        AtomicInteger importedRows = new AtomicInteger(0);
        AtomicInteger rejectedRows = new AtomicInteger(0);
        int rowNumber = 0;
        boolean hasHeader = false;
        Map<String, Integer> columnIndex = new HashMap<>();

        // Jeśli frontend przekazał mapowanie kolumn, użyj go bezpośrednio
        Map<String, Integer> explicitMapping = parseColumnMappingJson(columnMappingJson);

        // Wyznacz separator – obsługa \t jako tabulatora
        char sep = (columnSeparator == null || columnSeparator.isEmpty()) ? ','
                : (columnSeparator.equals("\\t") ? '\t' : columnSeparator.charAt(0));

        // Wyznacz znak cytowania – pusty string = '\0' (brak cytowania)
        char quote = (quoteChar == null || quoteChar.isEmpty()) ? '\0' : quoteChar.charAt(0);

        try (Reader reader = stripBom(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            CSVParserBuilder parserBuilder = new CSVParserBuilder().withSeparator(sep);
            if (quote != '\0') {
                parserBuilder.withQuoteChar(quote);
            } else {
                parserBuilder.withIgnoreQuotations(true);
            }
            CSVParser parser = parserBuilder.build();
            CSVReader csvReader = new CSVReaderBuilder(reader)
                    .withCSVParser(parser)
                    .build();

            // Gdy frontend przekazał explicitMapping, używamy go i pomijamy auto-detekcję nagłówka
            if (!explicitMapping.isEmpty()) {
                columnIndex = explicitMapping;
                log.debug("[CampaignImport] Używam mapowania z frontendu: {}", columnIndex);
            }

            String[] line;
            while ((line = csvReader.readNext()) != null) {
                rowNumber++;

                // Pierwszy wiersz: auto-detekcja nagłówka tylko gdy brak explicitMapping
                if (rowNumber == 1 && explicitMapping.isEmpty()) {
                    if (isHeader(line)) {
                        hasHeader = true;
                        columnIndex = buildColumnIndex(line);
                        log.debug("[CampaignImport] Wykryto nagłówek CSV: kolumny={}", columnIndex);
                        continue; // pomiń wiersz nagłówkowy
                    }
                    // Brak nagłówka – wiersz 1 to dane
                    columnIndex = defaultColumnIndex();
                }

                int dataRowNumber = hasHeader ? rowNumber - 1 : rowNumber;
                totalRows.incrementAndGet();

                // Parsuj wiersz i przetwórz (walidacja telefonu + dodanie do batcha) – logika
                // współdzielona z importem JSON, patrz {@link #processRow}
                CsvRow csvRow = parseRow(line, columnIndex);
                processRow(dataRowNumber, csvRow, campaignId, tenantId, batch, jobStatus, rejectedRows);

                // Flush batcha po BATCH_SIZE wierszach (z aktualizacją postępu w Redis)
                if (batch.size() >= BATCH_SIZE) {
                    flushBatch(jobId, campaignId, tenantId, batch, skipDuplicates, jobStatus,
                            totalRows, importedRows, rejectedRows, true);
                }
            }

            // Flush pozostałych (bez aktualizacji postępu – finalizeJobStatus zrobi to za chwilę)
            flushBatch(jobId, campaignId, tenantId, batch, skipDuplicates, jobStatus,
                    totalRows, importedRows, rejectedRows, false);
        }

        finalizeJobStatus(jobId, jobStatus, totalRows.get(), importedRows.get(), rejectedRows.get());
    }

    // =========================================================================
    // Logika importu JSON
    // =========================================================================

    /**
     * Główna logika parsowania JSON i batch insertu.
     *
     * <p>W odróżnieniu od CSV (parsowanie strumieniowe), cały plik JSON jest wczytywany do pamięci
     * jako {@code List<Map<String,Object>>} (limit rozmiaru pliku gwarantowany przez
     * {@link #validateJsonFile}) – dzięki temu {@code totalRows} jest znane z góry
     * ({@code rows.size()}), zanim rozpocznie się przetwarzanie wierszy.
     *
     * <p>Reszta logiki (walidacja telefonu, budowanie wiersza batcha, flush co
     * {@value #BATCH_SIZE} rekordów, finalizacja statusu) jest w pełni współdzielona z CSV przez
     * {@link #processRow}, {@link #flushBatch} i {@link #finalizeJobStatus}.
     *
     * @param jobId          UUID joba (do aktualizacji postępu)
     * @param campaignId     UUID kampanii
     * @param tenantId       UUID tenanta
     * @param file           plik JSON (tablica obiektów kontaktów)
     * @param skipDuplicates strategia deduplikacji
     * @param jobStatus      obiekt statusu (mutowany w trakcie przetwarzania)
     * @throws IOException gdy root JSON nie jest tablicą lub plik jest niepoprawnym JSON-em –
     *                      propaguje się do {@link #processJsonImportAsync}, kończąc job jako
     *                      {@code FAILED_PARTIAL} z próbką {@code "FATAL: ..."} (ten sam mechanizm
     *                      co błąd parsowania CSV)
     */
    private void doJsonImport(
            UUID jobId,
            UUID campaignId,
            UUID tenantId,
            MultipartFile file,
            boolean skipDuplicates,
            ImportJobStatus jobStatus) throws IOException {

        List<Map<String, Object>> rows;
        try (var inputStream = file.getInputStream()) {
            JsonNode root = objectMapper.readTree(inputStream);
            if (!root.isArray()) {
                throw new IOException("Plik JSON musi zawierać tablicę obiektów kontaktów.");
            }
            rows = objectMapper.convertValue(root, new TypeReference<List<Map<String, Object>>>() {});
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Błąd parsowania JSON: " + e.getMessage(), e);
        }

        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        AtomicInteger totalRows = new AtomicInteger(0);
        AtomicInteger importedRows = new AtomicInteger(0);
        AtomicInteger rejectedRows = new AtomicInteger(0);
        int totalRowsCount = rows.size();

        for (int i = 0; i < rows.size(); i++) {
            int dataRowNumber = i + 1;
            totalRows.incrementAndGet();

            // Element tablicy może być JSON `null` (np. [{"phone":"..."}, null]) – traktujemy go
            // jak wiersz bez żadnych danych zamiast rzucać NPE z parseJsonRow(null). Dzięki temu
            // trafia do istniejącej walidacji telefonu w processRow i zostaje po prostu odrzucony
            // (rejectedRows++), zamiast zabijać NPE-em cały job (FAILED_PARTIAL z utratą WSZYSTKICH
            // poprawnych wierszy) – wyjątek propagowałby się do catch-all w processJsonImportAsync,
            // bo lokalny try/catch w tej metodzie obejmuje tylko parsowanie JSON-a na starcie.
            Map<String, Object> rawRow = rows.get(i);
            CsvRow csvRow = rawRow != null ? parseJsonRow(rawRow) : new CsvRow(null, null, null, Map.of());
            processRow(dataRowNumber, csvRow, campaignId, tenantId, batch, jobStatus, rejectedRows);

            if (batch.size() >= BATCH_SIZE) {
                flushBatch(jobId, campaignId, tenantId, batch, skipDuplicates, jobStatus,
                        totalRows, importedRows, rejectedRows, true);
            }
        }

        // Flush pozostałych
        flushBatch(jobId, campaignId, tenantId, batch, skipDuplicates, jobStatus,
                totalRows, importedRows, rejectedRows, false);

        finalizeJobStatus(jobId, jobStatus, totalRowsCount, importedRows.get(), rejectedRows.get());
    }

    /**
     * Mapuje jeden obiekt JSON (camelCase) na wewnętrzny neutralny format {@link CsvRow},
     * współdzielony z importem CSV.
     *
     * <p>Format wejściowy: {@code {"phone": "+48...", "firstName": "...", "lastName": "...",
     * "customFields": {"klucz": "wartość", ...}}}.
     */
    private CsvRow parseJsonRow(Map<String, Object> obj) {
        String phone     = jsonStringOrNull(obj.get("phone"));
        String firstName = jsonStringOrNull(obj.get("firstName"));
        String lastName  = jsonStringOrNull(obj.get("lastName"));

        Map<String, String> customFields = new LinkedHashMap<>();
        if (obj.get("customFields") instanceof Map<?, ?> customFieldsMap) {
            for (Map.Entry<?, ?> entry : customFieldsMap.entrySet()) {
                if (entry.getValue() != null) {
                    customFields.put(String.valueOf(entry.getKey()), customFieldValueToString(entry.getValue()));
                }
            }
        }

        return new CsvRow(phone, firstName, lastName, customFields);
    }

    /**
     * Konwertuje pojedynczą wartość pola {@code customFields} na String do zapisu w
     * {@code custom_fields} (JSONB).
     *
     * <p>Wartości skalarne ({@link String}, {@link Number}, {@link Boolean}) są konwertowane przez
     * zwykłe {@code String.valueOf} (zachowanie dotychczasowe). Wartości złożone (zagnieżdżona
     * lista/mapa, np. {@code {"tags": ["a","b"]}}) muszą być serializowane jako poprawny JSON
     * przez {@link #objectMapper} – w przeciwnym razie {@code String.valueOf} produkuje
     * niepoprawny JSON (Java {@code toString()}, np. {@code "[a, b]"} zamiast {@code ["a","b"]}).
     * Fallback do {@code String.valueOf} przy błędzie serializacji – analogicznie do {@link #toJson}.
     */
    private String customFieldValueToString(Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[CampaignImport] Błąd serializacji wartości customFields: {}", e.getMessage());
            return String.valueOf(value);
        }
    }

    /**
     * Konwertuje wartość pola JSON na String, traktując puste/blank jako brak danych (null).
     * Przycina białe znaki – spójnie z {@link #getColumn} używanym po stronie CSV.
     */
    private String jsonStringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    // =========================================================================
    // Logika importu – wspólna dla CSV i JSON
    // =========================================================================

    /**
     * Przetwarza pojedynczy wiersz importu (CSV lub JSON) – logika współdzielona przez obie
     * ścieżki: walidacja telefonu E.164, odrzucenie (próbka w {@code jobStatus} + inkrementacja
     * {@code rejectedRows}) albo dodanie zbudowanego wiersza do batcha.
     *
     * @param dataRowNumber numer wiersza danych (do raportu odrzuceń) – 1-indeksowany
     * @param csvRow        sparsowany wiersz (format neutralny – współdzielony przez CSV i JSON)
     * @param campaignId    UUID kampanii
     * @param tenantId      UUID tenanta
     * @param batch         batch wierszy do wstawienia (mutowany)
     * @param jobStatus     status joba – używany do zapisu próbek odrzuconych wierszy (mutowany)
     * @param rejectedRows  licznik odrzuconych wierszy (mutowany)
     */
    private void processRow(int dataRowNumber, CsvRow csvRow, UUID campaignId, UUID tenantId,
                            List<Object[]> batch, ImportJobStatus jobStatus, AtomicInteger rejectedRows) {

        // Walidacja telefonu
        if (csvRow.phone() == null || !isValidE164(csvRow.phone())) {
            String reason = String.format("row %d: invalid phone %s",
                    dataRowNumber,
                    csvRow.phone() != null ? csvRow.phone() : "(empty)");
            jobStatus.addRejectedSample(reason);
            rejectedRows.incrementAndGet();
            log.debug("[CampaignImport] Odrzucono wiersz: {}", reason);
            return;
        }

        // Dodaj do batcha
        batch.add(buildRow(campaignId, tenantId, csvRow));
    }

    /**
     * Wstawia bieżący batch do bazy (jeśli niepusty) i, opcjonalnie, aktualizuje postęp joba
     * w Redis – logika współdzielona przez CSV i JSON.
     *
     * @param jobId          UUID joba
     * @param campaignId     UUID kampanii
     * @param tenantId       UUID tenanta
     * @param batch          batch wierszy do wstawienia (czyszczony po insertcie)
     * @param skipDuplicates strategia deduplikacji
     * @param jobStatus      status joba (mutowany, gdy {@code updateProgress=true})
     * @param totalRows      licznik przetworzonych dotąd wierszy
     * @param importedRows   licznik zaimportowanych wierszy (mutowany)
     * @param rejectedRows   licznik odrzuconych wierszy
     * @param updateProgress true = zapisz aktualny postęp do Redis (flush w trakcie pętli po
     *                       osiągnięciu {@value #BATCH_SIZE}); false = tylko wstaw resztki batcha
     *                       bez zapisu postępu (końcowy flush – finalizeJobStatus zrobi to za chwilę)
     */
    private void flushBatch(UUID jobId, UUID campaignId, UUID tenantId, List<Object[]> batch,
                            boolean skipDuplicates, ImportJobStatus jobStatus,
                            AtomicInteger totalRows, AtomicInteger importedRows, AtomicInteger rejectedRows,
                            boolean updateProgress) {

        if (batch.isEmpty()) {
            return;
        }

        int inserted = campaignContactRepository.batchInsert(tenantId, campaignId, batch, skipDuplicates);
        importedRows.addAndGet(inserted);
        batch.clear();

        if (updateProgress) {
            jobStatus.setProcessedRows(totalRows.get());
            jobStatus.setImportedRows(importedRows.get());
            jobStatus.setRejectedRows(rejectedRows.get());
            saveJobStatus(jobId, jobStatus);

            log.debug("[CampaignImport] Chunk: jobId={}, przetworzono={}, zaimportowano={}, odrzucono={}",
                    jobId, totalRows.get(), importedRows.get(), rejectedRows.get());
        }
    }

    /**
     * Finalizuje status joba po przetworzeniu wszystkich wierszy (CSV lub JSON) – ustala status
     * końcowy (COMPLETED/FAILED_PARTIAL), zapisuje liczniki do Redis i loguje podsumowanie.
     *
     * @param jobId              UUID joba
     * @param jobStatus          status joba (mutowany)
     * @param totalRowsCount     łączna liczba wierszy danych (bez nagłówka CSV)
     * @param importedRowsCount  liczba faktycznie wstawionych/zaktualizowanych rekordów
     * @param rejectedRowsCount  liczba wierszy odrzuconych walidacją telefonu
     */
    private void finalizeJobStatus(UUID jobId, ImportJobStatus jobStatus,
                                   int totalRowsCount, int importedRowsCount, int rejectedRowsCount) {

        // Liczba pominięta przez deduplikację (nie odrzucona ze względu na walidację)
        int skippedByDeduplication = totalRowsCount - rejectedRowsCount - importedRowsCount;

        ImportJobStatus.Status finalStatus = rejectedRowsCount > 0
                ? ImportJobStatus.Status.FAILED_PARTIAL
                : ImportJobStatus.Status.COMPLETED;

        jobStatus.setStatus(finalStatus);
        jobStatus.setTotalRows(totalRowsCount);
        jobStatus.setProcessedRows(totalRowsCount);
        jobStatus.setImportedRows(importedRowsCount);
        jobStatus.setRejectedRows(rejectedRowsCount);
        jobStatus.setCompletedAt(Instant.now());
        saveJobStatus(jobId, jobStatus);

        log.info("[CampaignImport] Import zakończony: jobId={}, status={}, total={}, " +
                        "imported={}, rejected={}, skippedDuplicates={}, duration={}ms",
                jobId, finalStatus, totalRowsCount, importedRowsCount, rejectedRowsCount,
                skippedByDeduplication,
                jobStatus.getStartedAt() != null
                        ? Duration.between(jobStatus.getStartedAt(), Instant.now()).toMillis()
                        : "N/A");
    }

    /**
     * Waliduje przynależność kampanii do bieżącego tenanta (cross-tenant check).
     *
     * @throws EntityNotFoundException gdy kampania nie istnieje lub należy do innego tenanta
     */
    private void requireCampaignInTenant(UUID campaignId, UUID tenantId) {
        campaignRepository.findById(campaignId, tenantId)
                .orElseThrow(() -> new EntityNotFoundException(
                        String.format("Kampania o ID %s nie istnieje lub brak dostępu.", campaignId)));
    }

    /**
     * Buduje początkowy status joba (QUEUED) – wspólny dla CSV i JSON.
     */
    private ImportJobStatus buildQueuedStatus(UUID jobId, UUID campaignId) {
        return ImportJobStatus.builder()
                .jobId(jobId)
                .campaignId(campaignId)
                .status(ImportJobStatus.Status.QUEUED)
                .totalRows(0)
                .processedRows(0)
                .importedRows(0)
                .rejectedRows(0)
                .rejectedSamples(new ArrayList<>())
                .startedAt(null)
                .completedAt(null)
                .build();
    }

    // =========================================================================
    // Pobieranie statusu
    // =========================================================================

    /**
     * Pobiera status joba z Redis.
     *
     * @param jobId UUID joba
     * @return status joba lub null gdy nie istnieje
     */
    @Override
    public ImportJobStatus getJobStatus(UUID jobId) {
        return loadJobStatus(jobId);
    }

    // =========================================================================
    // Dostęp do encji (encapsulation pass – pkt 9 wzorca)
    // =========================================================================

    @Override
    public PagedResponse<CampaignContactResponse> getCampaignContacts(
            UUID tenantId, UUID campaignId, String statusFilter, int page, int size) {
        return campaignContactRepository.findByCampaign(tenantId, campaignId, statusFilter, page, size);
    }

    // =========================================================================
    // Pomocnicze – Redis
    // =========================================================================

    /**
     * Zapisuje status joba do Redis z TTL 1h.
     */
    private void saveJobStatus(UUID jobId, ImportJobStatus status) {
        try {
            String key = JOB_KEY_PREFIX + jobId;
            String json = objectMapper.writeValueAsString(status);
            stringRedisTemplate.opsForValue().set(key, json, Duration.ofSeconds(JOB_TTL_SECONDS));
        } catch (Exception e) {
            log.error("[CampaignImport] Błąd zapisu statusu do Redis: jobId={}, error={}", jobId, e.getMessage());
        }
    }

    /**
     * Ładuje status joba z Redis.
     */
    private ImportJobStatus loadJobStatus(UUID jobId) {
        try {
            String key = JOB_KEY_PREFIX + jobId;
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, ImportJobStatus.class);
        } catch (Exception e) {
            log.error("[CampaignImport] Błąd odczytu statusu z Redis: jobId={}, error={}", jobId, e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // Pomocnicze – walidacja i parsowanie
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

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".csv")) {
            throw new IllegalArgumentException(
                    "Dozwolone są tylko pliki CSV (.csv). Przesłany plik: " + originalFilename);
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

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".json")) {
            throw new IllegalArgumentException(
                    "Dozwolone są tylko pliki JSON (.json). Przesłany plik: " + originalFilename);
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    String.format("Plik JSON jest za duży: %d MB. Maksymalny dozwolony rozmiar: 50 MB.",
                            file.getSize() / (1024 * 1024)));
        }
    }

    /**
     * Sprawdza czy pierwszy wiersz CSV jest nagłówkiem.
     * Kryterium: przynajmniej jedna kolumna ma wartość "phone", "first_name" lub "last_name".
     */
    private boolean isHeader(String[] line) {
        for (String col : line) {
            String lower = col.trim().toLowerCase();
            if (lower.equals(COL_PHONE) || lower.equals(COL_FIRST_NAME) || lower.equals(COL_LAST_NAME)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Buduje mapę nazwy kolumny → indeks na podstawie wiersza nagłówkowego.
     */
    private Map<String, Integer> buildColumnIndex(String[] header) {
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            index.put(header[i].trim().toLowerCase(), i);
        }
        return index;
    }

    /**
     * Domyślny indeks kolumn gdy brak nagłówka (0=phone, 1=first_name, 2=last_name).
     */
    private Map<String, Integer> defaultColumnIndex() {
        Map<String, Integer> index = new HashMap<>();
        index.put(COL_PHONE, 0);
        index.put(COL_FIRST_NAME, 1);
        index.put(COL_LAST_NAME, 2);
        return index;
    }

    /**
     * Parsuje wiersz CSV na model CsvRow na podstawie mapy indeksów kolumn.
     */
    private CsvRow parseRow(String[] line, Map<String, Integer> columnIndex) {
        String phone     = getColumn(line, columnIndex, COL_PHONE);
        String firstName = getColumn(line, columnIndex, COL_FIRST_NAME);
        String lastName  = getColumn(line, columnIndex, COL_LAST_NAME);

        // Niestandardowe pola: custom_field_1, custom_field_2, ...
        Map<String, String> customFields = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : columnIndex.entrySet()) {
            String colName = entry.getKey();
            if (colName.startsWith("custom_field")) {
                String value = getColumn(line, columnIndex, colName);
                if (value != null && !value.isBlank()) {
                    customFields.put(colName, value);
                }
            }
        }

        return new CsvRow(phone, firstName, lastName, customFields);
    }

    /**
     * Bezpiecznie pobiera wartość kolumny z wiersza (null gdy poza zakresem lub puste).
     */
    private String getColumn(String[] line, Map<String, Integer> columnIndex, String columnName) {
        Integer idx = columnIndex.get(columnName);
        if (idx == null || idx >= line.length) {
            return null;
        }
        String val = line[idx].trim();
        return val.isEmpty() ? null : val;
    }

    /**
     * Buduje tablicę parametrów dla batch INSERT.
     *
     * <p>Kolejność parametrów: campaign_id, tenant_id, phone, first_name, last_name,
     * custom_fields (JSON), created_at.
     */
    private Object[] buildRow(UUID campaignId, UUID tenantId, CsvRow csvRow) {
        String customFieldsJson = toJson(csvRow.customFields());
        return new Object[]{
                campaignId.toString(),
                tenantId.toString(),
                csvRow.phone(),
                csvRow.firstName(),
                csvRow.lastName(),
                customFieldsJson,
                Timestamp.from(Instant.now())
        };
    }

    /**
     * Serializuje mapę do JSON (fallback do "{}" w razie błędu).
     */
    private String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("[CampaignImport] Błąd serializacji custom_fields: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * Parsuje JSON mapowania kolumn z frontendu.
     *
     * <p>Format wejściowy: {@code {"phone":2,"first_name":0,"last_name":1}}
     * – klucz to nazwa pola systemowego, wartość to indeks kolumny CSV (0-based).
     *
     * @param json JSON string lub null/pusty
     * @return mapa systemField → indeks kolumny; pusta gdy json jest null/pusty/błędny
     */
    @SuppressWarnings("unchecked")
    Map<String, Integer> parseColumnMappingJson(String json) {
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
            log.debug("[CampaignImport] Sparsowano mapowanie kolumn: {}", result);
            return result;
        } catch (Exception e) {
            log.warn("[CampaignImport] Błąd parsowania columnMapping JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Waliduje numer telefonu w formacie E.164: +[1-15 cyfr].
     *
     * @param phone numer telefonu
     * @return true gdy format jest poprawny
     */
    public boolean isValidE164(String phone) {
        if (phone == null || phone.isBlank()) {
            return false;
        }
        return E164_PATTERN.matcher(phone.trim()).matches();
    }

    /**
     * Zwraca Reader ze stripowanym BOM (U+FEFF) na początku strumienia.
     *
     * <p>Pliki CSV zapisane w "UTF-8 with BOM" (np. przez Microsoft Excel) zawierają
     * na początku bajty EF BB BF, które Java dekoduje jako znak U+FEFF. Bez stripowania
     * BOM trafia do pierwszego tokena (nazwę kolumny nagłówka lub wartość pierwszego pola),
     * co powoduje niepoprawną detekcję nagłówka i odrzucenie numeru telefonu przez walidację E.164.
     *
     * <p>Metoda używa {@link PushbackReader} – odczytuje jeden znak i, jeśli to BOM,
     * pomija go; w przeciwnym razie cofa go z powrotem do strumienia. Nie wymaga żadnych
     * dodatkowych zależności ani buforowania całego pliku w pamięci.
     *
     * @param reader oryginalny reader (np. InputStreamReader)
     * @return reader bez wiodącego BOM
     */
    private Reader stripBom(Reader reader) throws IOException {
        PushbackReader pbr = new PushbackReader(reader, 1);
        int firstChar = pbr.read();
        if (firstChar != '\uFEFF') {
            // Nie BOM – wróć znak do strumienia
            if (firstChar != -1) {
                pbr.unread(firstChar);
            }
        }
        // Jeśli firstChar == '\uFEFF', po prostu go pomijamy
        return pbr;
    }

    // =========================================================================
    // Wewnętrzny record – model wiersza CSV
    // =========================================================================

    /**
     * Niemutowalny model danych jednego wiersza CSV.
     */
    private record CsvRow(
            String phone,
            String firstName,
            String lastName,
            Map<String, String> customFields
    ) {}
}
