package com.contactcenter.domain.campaign;

import com.contactcenter.domain.repository.TenantAwareRepository;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.campaign.dto.CampaignContactResponse;
import com.contactcenter.security.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Repozytorium dla tabeli {@code campaign_contact} – lista kontaktów kampanii.
 *
 * <p>Używa {@link JdbcTemplate} zamiast EntityManager/JPA, ponieważ tabela
 * jest partycjonowana po {@code campaign_id} (LIST partitioning) i wymaga
 * natywnego batch INSERT dla wydajności (BE-023: 100k rekordów w < 2 min).
 *
 * <p>Rozszerza {@link TenantAwareRepository} – wszystkie metody ustawiają
 * kontekst RLS przez {@code setTenantContextInDb(tenantId)}.
 *
 * <p>Uwaga: {@code setTenantContextInDb()} z TenantAwareRepository używa EntityManager,
 * podczas gdy ten repozytory używa JdbcTemplate. RLS jest ustawiane przez natywne
 * zapytanie przed operacjami JdbcTemplate w tej samej transakcji.
 */
@Slf4j
@Repository
class CampaignContactRepository extends TenantAwareRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CampaignContactRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // Batch INSERT
    // =========================================================================

    /**
     * Wstawia chunk rekordów kontaktów w jednej transakcji.
     *
     * <p>Używa {@code INSERT ... ON CONFLICT DO NOTHING} dla deduplikacji
     * po (campaign_id, phone) gdy {@code skipDuplicates=true},
     * lub {@code INSERT ... ON CONFLICT DO UPDATE} gdy {@code skipDuplicates=false}.
     *
     * <p>Format rekordu: {@code Object[]{campaignId, tenantId, phone, firstName, lastName, customFieldsJson}}.
     *
     * @param tenantId       UUID tenanta (do RLS)
     * @param campaignId     UUID kampanii
     * @param rows           lista wierszy do wstawienia – każdy wiersz to tablica Object[]
     * @param skipDuplicates true = pomijaj duplikaty (ON CONFLICT DO NOTHING),
     *                       false = aktualizuj istniejące (ON CONFLICT DO UPDATE)
     * @return liczba faktycznie wstawionych/zaktualizowanych rekordów
     */
    @Transactional
    public int batchInsert(UUID tenantId, UUID campaignId, List<Object[]> rows, boolean skipDuplicates) {
        if (rows.isEmpty()) {
            return 0;
        }

        setTenantContextInDb(tenantId);

        // Wymuszamy kontekst RLS dla JdbcTemplate (ta sama sesja/transakcja)
        jdbcTemplate.execute("SELECT set_tenant_context(?::uuid)", (PreparedStatementCallback<Void>) ps -> { ps.setString(1, tenantId.toString()); ps.execute(); return null; });

        String sql = buildInsertSql(skipDuplicates);

        // JdbcTemplate.batchUpdate(String, List<Object[]>) zwraca int[] – jeden wynik per wiersz
        int[] batchResult = jdbcTemplate.batchUpdate(sql, rows);

        int total = 0;
        for (int count : batchResult) {
            // EXECUTE_FAILED = -3, SUCCESS_NO_INFO = -2
            if (count >= 0) {
                total += count;
            } else if (count == java.sql.Statement.SUCCESS_NO_INFO) {
                // Sterownik nie zwraca liczby wierszy – liczymy jako 1
                total += 1;
            }
            // count == EXECUTE_FAILED (-3) → wiersz nie wstawiony (duplikat przy skipDuplicates)
        }

        log.debug("[CampaignContactRepo] Batch insert: campaignId={}, wierszy podanych={}, zaimportowanych={}",
                campaignId, rows.size(), total);

        return total;
    }

    /**
     * Buduje SQL INSERT z odpowiednią klauzulą ON CONFLICT.
     */
    private String buildInsertSql(boolean skipDuplicates) {
        String baseInsert = """
                INSERT INTO campaign_contact
                    (record_id, campaign_id, tenant_id, phone, first_name, last_name,
                     custom_fields, status, created_at)
                VALUES
                    (gen_random_uuid(), ?::uuid, ?::uuid, ?, ?, ?, ?::jsonb, 'PENDING', ?)
                """;

        if (skipDuplicates) {
            // Deduplikacja: jeśli rekord z tym samym (campaign_id, phone) istnieje – pomijamy
            return baseInsert + """
                    ON CONFLICT (campaign_id, phone)
                    WHERE phone IS NOT NULL
                    DO NOTHING
                    """;
        } else {
            // Upsert: aktualizuj first_name, last_name, custom_fields
            return baseInsert + """
                    ON CONFLICT (campaign_id, phone)
                    WHERE phone IS NOT NULL
                    DO UPDATE SET
                        first_name    = EXCLUDED.first_name,
                        last_name     = EXCLUDED.last_name,
                        custom_fields = EXCLUDED.custom_fields,
                        updated_at    = NOW()
                    """;
        }
    }

    // =========================================================================
    // Odczyt – deduplikacja
    // =========================================================================

    /**
     * Pobiera zbiór numerów telefonów już istniejących w kampanii.
     *
     * <p>Używane do pre-filtrowania przed batch insertem, gdy chcemy zliczyć
     * pominięte duplikaty (ON CONFLICT DO NOTHING nie zwraca ile pominęło).
     *
     * @param tenantId   UUID tenanta
     * @param campaignId UUID kampanii
     * @return zbiór numerów telefonów (może być pusty)
     */
    @Transactional(readOnly = true)
    public Set<String> findExistingPhones(UUID tenantId, UUID campaignId) {
        setTenantContextInDb(tenantId);
        jdbcTemplate.execute("SELECT set_tenant_context(?::uuid)", (PreparedStatementCallback<Void>) ps -> { ps.setString(1, tenantId.toString()); ps.execute(); return null; });

        List<String> phones = jdbcTemplate.queryForList(
                "SELECT phone FROM campaign_contact WHERE campaign_id = ?::uuid AND tenant_id = ?::uuid AND phone IS NOT NULL",
                String.class,
                campaignId.toString(),
                tenantId.toString()
        );

        return new java.util.HashSet<>(phones);
    }

    // =========================================================================
    // Odczyt – zliczanie statusów per kampania (dialer status, jedno zapytanie GROUP BY)
    // =========================================================================

    /**
     * Zlicza rekordy campaign_contact pogrupowane po (campaign_id, status) jednym zapytaniem SQL.
     *
     * <p>Zastępuje wzorzec N+1 w {@code DialerController.getDialerStatus()}, gdzie dla każdej
     * kampanii i każdego statusu wykonywano osobne zapytanie COUNT. Teraz jedno zapytanie
     * GROUP BY obsługuje wszystkie kampanie i statusy naraz.
     *
     * <p>Zwraca mapę {@code campaign_id → (status → count)}.
     *
     * @param tenantId    UUID tenanta (do RLS)
     * @param campaignIds lista UUID kampanii (musi być niepusta)
     * @param statuses    lista statusów do filtrowania (np. PENDING, DIALING, COMPLETED, NO_ANSWER, FAILED)
     * @return mapa campaign_id → mapa status → liczba rekordów
     */
    @Transactional(readOnly = true)
    public Map<UUID, Map<String, Long>> countByStatusGroupedByCampaign(
            UUID tenantId, List<UUID> campaignIds, List<String> statuses) {
        if (campaignIds == null || campaignIds.isEmpty()) {
            return java.util.Collections.emptyMap();
        }

        setTenantContextInDb(tenantId);
        jdbcTemplate.execute("SELECT set_tenant_context(?::uuid)", (PreparedStatementCallback<Void>) ps -> { ps.setString(1, tenantId.toString()); ps.execute(); return null; });

        // Buduj placeholdery dla campaign_id IN (...)
        String campaignPlaceholders = campaignIds.stream()
                .map(id -> "?::uuid")
                .collect(java.util.stream.Collectors.joining(", "));

        // Buduj placeholdery dla status IN (...)
        String statusPlaceholders = statuses.stream()
                .map(s -> "?")
                .collect(java.util.stream.Collectors.joining(", "));

        String sql = "SELECT campaign_id::text, status, COUNT(*) AS cnt " +
                     "FROM campaign_contact " +
                     "WHERE tenant_id = ?::uuid " +
                     "  AND campaign_id IN (" + campaignPlaceholders + ") " +
                     "  AND status IN (" + statusPlaceholders + ") " +
                     "GROUP BY campaign_id, status";

        List<Object> params = new ArrayList<>();
        params.add(tenantId.toString());
        campaignIds.forEach(id -> params.add(id.toString()));
        params.addAll(statuses);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params.toArray());

        Map<UUID, Map<String, Long>> result = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID cid = UUID.fromString((String) row.get("campaign_id"));
            String status = (String) row.get("status");
            long count = ((Number) row.get("cnt")).longValue();
            result.computeIfAbsent(cid, k -> new java.util.LinkedHashMap<>()).put(status, count);
        }

        log.debug("[CampaignContactRepo] countByStatusGroupedByCampaign: tenant={}, kampanie={}, statuses={}",
                tenantId, campaignIds.size(), statuses);

        return result;
    }

    // =========================================================================
    // Odczyt – weryfikacja i aktualizacja rekordu przy manualnym połączeniu
    // =========================================================================

    /**
     * Pobiera rekord campaign_contact z weryfikacją tenanta.
     *
     * <p>Używane przez logikę manualnego dialera do weryfikacji statusu PENDING
     * przed zainicjowaniem połączenia.
     *
     * @param recordId   UUID rekordu
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     * @return Optional z mapą kolumn (record_id, phone, status) lub empty gdy nie znaleziono
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Map<String, Object>> findRecordForManualDial(
            UUID recordId, UUID campaignId, UUID tenantId) {
        setTenantContextInDb(tenantId);
        jdbcTemplate.execute("SELECT set_tenant_context(?::uuid)", (PreparedStatementCallback<Void>) ps -> { ps.setString(1, tenantId.toString()); ps.execute(); return null; });

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT record_id::text, phone, status, next_attempt_at
                FROM campaign_contact
                WHERE record_id = ?::uuid
                  AND campaign_id = ?::uuid
                  AND tenant_id = ?::uuid
                """,
                recordId.toString(),
                campaignId.toString(),
                tenantId.toString()
        );

        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    /**
     * Aktualizuje status rekordu campaign_contact na DIALING (przy starcie połączenia).
     *
     * @param recordId   UUID rekordu
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     */
    @Transactional
    public void markAsDialing(UUID recordId, UUID campaignId, UUID tenantId) {
        setTenantContextInDb(tenantId);
        jdbcTemplate.execute("SELECT set_tenant_context(?::uuid)", (PreparedStatementCallback<Void>) ps -> { ps.setString(1, tenantId.toString()); ps.execute(); return null; });

        jdbcTemplate.update(
                """
                UPDATE campaign_contact
                SET status = 'DIALING',
                    last_attempt_at = NOW(),
                    next_attempt_at = NULL,
                    attempt_count = attempt_count + 1,
                    updated_at = NOW()
                WHERE record_id = ?::uuid
                  AND campaign_id = ?::uuid
                  AND tenant_id = ?::uuid
                """,
                recordId.toString(),
                campaignId.toString(),
                tenantId.toString()
        );
    }

    /**
     * Aktualizuje status rekordu campaign_contact na DIALING przy realizacji callbacku kampanijnego.
     *
     * <p>Różni się od {@link #markAsDialing} tym, że <strong>NIE inkrementuje {@code attempt_count}</strong> –
     * oddzwonienie to callback attempt, a nie nowa próba dialera w kampanii.
     *
     * @param recordId   UUID rekordu
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     */
    @Transactional
    public void markAsDialingForCallback(UUID recordId, UUID campaignId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        jdbcTemplate.update(
                """
                UPDATE campaign_contact
                SET status = 'DIALING',
                    last_attempt_at = NOW(),
                    updated_at = NOW()
                WHERE record_id = ?::uuid
                  AND campaign_id = ?::uuid
                  AND tenant_id = ?::uuid
                """,
                recordId.toString(),
                campaignId.toString(),
                tenantId.toString()
        );
    }

    /**
     * Cofa status rekordu z DIALING z powrotem na CALLBACK po błędzie telefonii podczas callback attempt.
     *
     * <p>Wywoływane gdy {@code ScheduledCallbackExecutor} zdążył ustawić DIALING, ale
     * {@code TelephonyAdapter.initiateCall()} rzucił wyjątek. Przywraca {@code next_attempt_at}
     * do wartości {@code scheduledAt} callbacku, aby rekord mógł wrócić do kolejki dialera.
     *
     * @param recordId      UUID rekordu
     * @param campaignId    UUID kampanii
     * @param tenantId      UUID tenanta
     * @param nextAttemptAt moment kolejnej próby (scheduledAt z callbacku)
     */
    @Transactional
    public void revertDialingToCallback(UUID recordId, UUID campaignId, UUID tenantId, Instant nextAttemptAt) {
        setTenantContextInDb(tenantId);

        jdbcTemplate.update(
                """
                UPDATE campaign_contact
                SET status = 'CALLBACK',
                    next_attempt_at = ?,
                    updated_at = NOW()
                WHERE record_id = ?::uuid
                  AND campaign_id = ?::uuid
                  AND tenant_id = ?::uuid
                """,
                java.sql.Timestamp.from(nextAttemptAt),
                recordId.toString(),
                campaignId.toString(),
                tenantId.toString()
        );
    }

    /**
     * Oznacza rekord campaign_contact jako ERROR (trwały błąd techniczny adaptera telefonii).
     *
     * <p>Używane gdy Twilio API rzuci {@code ApiException} podczas {@code initiateCall()}
     * (np. kod 21219 – niezweryfikowany numer). Rekord nie wraca do PENDING, ponieważ
     * błąd konfiguracyjny nie ustąpi przy kolejnej próbie.
     *
     * @param recordId   UUID rekordu
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     */
    @Transactional
    public void markAsError(UUID recordId, UUID campaignId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        jdbcTemplate.update(
                """
                UPDATE campaign_contact
                SET status = 'ERROR',
                    updated_at = NOW()
                WHERE record_id = ?::uuid
                  AND campaign_id = ?::uuid
                  AND tenant_id = ?::uuid
                """,
                recordId.toString(),
                campaignId.toString(),
                tenantId.toString()
        );
    }

    /**
     * Wycofuje status rekordu z DIALING z powrotem na PENDING (rollback przy błędzie telefonii).
     *
     * @param recordId   UUID rekordu
     * @param campaignId UUID kampanii
     * @param tenantId   UUID tenanta
     */
    @Transactional
    public void revertDialingToPending(UUID recordId, UUID campaignId, UUID tenantId) {
        setTenantContextInDb(tenantId);
        jdbcTemplate.execute("SELECT set_tenant_context(?::uuid)", (PreparedStatementCallback<Void>) ps -> { ps.setString(1, tenantId.toString()); ps.execute(); return null; });

        jdbcTemplate.update(
                """
                UPDATE campaign_contact
                SET status = 'PENDING',
                    attempt_count = GREATEST(attempt_count - 1, 0),
                    updated_at = NOW()
                WHERE record_id = ?::uuid
                  AND campaign_id = ?::uuid
                  AND tenant_id = ?::uuid
                """,
                recordId.toString(),
                campaignId.toString(),
                tenantId.toString()
        );
    }

    // =========================================================================
    // Odczyt – rekordy PENDING dla kampanii manualnych (widok agenta)
    // =========================================================================

    /**
     * Pobiera rekordy dostępne do wybierania dla podanych kampanii manualnych (batch).
     *
     * <p>Zwraca rekordy w statusach PENDING, NO_ANSWER i FAILED, których {@code next_attempt_at}
     * jest w przeszłości lub null. Spójne z logiką ProgressiveDialerService.
     *
     * <p>Wyniki posortowane po campaign_id ASC, created_at ASC – ułatwia grupowanie
     * po stronie serwisu.
     *
     * @param tenantId    UUID tenanta (do RLS)
     * @param campaignIds lista UUID kampanii do sprawdzenia (musi być niepusta)
     * @return lista map kolumn: record_id, campaign_id, phone, first_name, last_name, status
     * @throws IllegalArgumentException gdy campaignIds jest pusta
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> findPendingByCampaignIds(UUID tenantId, List<UUID> campaignIds) {
        if (campaignIds == null || campaignIds.isEmpty()) {
            throw new IllegalArgumentException("campaignIds nie może być pusta");
        }

        setTenantContextInDb(tenantId);
        jdbcTemplate.execute("SELECT set_tenant_context(?::uuid)", (PreparedStatementCallback<Void>) ps -> { ps.setString(1, tenantId.toString()); ps.execute(); return null; });

        // Budujemy listę placeholderów dla IN: ?::uuid, ?::uuid, ...
        String placeholders = campaignIds.stream()
                .map(id -> "?::uuid")
                .collect(java.util.stream.Collectors.joining(", "));

        String sql = """
                SELECT record_id::text, campaign_id::text,
                       phone, first_name, last_name, status
                FROM campaign_contact
                WHERE tenant_id = ?::uuid
                  AND campaign_id IN (""" + placeholders + """
                )
                  AND status IN ('PENDING', 'NO_ANSWER', 'FAILED')
                  AND (next_attempt_at IS NULL OR next_attempt_at <= NOW())
                ORDER BY campaign_id ASC, created_at ASC
                """;

        // Parametry: tenantId + wszystkie campaignId
        List<Object> params = new ArrayList<>();
        params.add(tenantId.toString());
        campaignIds.forEach(id -> params.add(id.toString()));

        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, params.toArray());

        log.debug("[CampaignContactRepo] findPendingByCampaignIds: tenant={}, kampanie={}, znaleziono={}",
                tenantId, campaignIds.size(), results.size());

        return results;
    }

    // =========================================================================
    // Odczyt – paginowana lista kontaktów kampanii
    // =========================================================================

    /**
     * Zwraca paginowaną listę kontaktów dla danej kampanii.
     *
     * <p>Opcjonalny filtr {@code statusFilter} ogranicza wyniki do rekordów
     * o danym statusie (PENDING, CALLED, FAILED, SKIPPED).
     *
     * <p>Metoda wykonuje dwa zapytania w tej samej transakcji:
     * <ol>
     *   <li>COUNT – do obliczenia {@code totalElements}</li>
     *   <li>SELECT z LIMIT/OFFSET – do pobrania strony wyników</li>
     * </ol>
     *
     * @param tenantId     UUID tenanta (do RLS)
     * @param campaignId   UUID kampanii
     * @param statusFilter opcjonalny filtr statusu – {@code null} = brak filtru
     * @param page         numer strony (0-based)
     * @param size         rozmiar strony (max 200)
     * @return opakowana odpowiedź z metadanymi paginacji
     */
    @Transactional(readOnly = true)
    public PagedResponse<CampaignContactResponse> findByCampaign(
            UUID tenantId,
            UUID campaignId,
            String statusFilter,
            int page,
            int size
    ) {
        // Walidacja i normalizacja parametrów paginacji
        if (size > 200) {
            size = 200;
        }
        if (size < 1) {
            size = 1;
        }
        if (page < 0) {
            page = 0;
        }

        // Ustawienie kontekstu RLS (dwa sposoby: EntityManager + JdbcTemplate w tej samej transakcji)
        setTenantContextInDb(tenantId);
        jdbcTemplate.execute("SELECT set_tenant_context(?::uuid)", (PreparedStatementCallback<Void>) ps -> { ps.setString(1, tenantId.toString()); ps.execute(); return null; });

        boolean hasStatusFilter = statusFilter != null && !statusFilter.isBlank();

        // ---- COUNT ----
        String countSql;
        Object[] countParams;
        if (hasStatusFilter) {
            countSql = """
                    SELECT COUNT(*) FROM campaign_contact
                    WHERE campaign_id = ?::uuid
                      AND tenant_id = ?::uuid
                      AND status = ?
                    """;
            countParams = new Object[]{campaignId.toString(), tenantId.toString(), statusFilter};
        } else {
            countSql = """
                    SELECT COUNT(*) FROM campaign_contact
                    WHERE campaign_id = ?::uuid
                      AND tenant_id = ?::uuid
                    """;
            countParams = new Object[]{campaignId.toString(), tenantId.toString()};
        }

        long totalElements = jdbcTemplate.queryForObject(countSql, Long.class, countParams);

        if (totalElements == 0) {
            return new PagedResponse<>(Collections.emptyList(), page, size, 0L, 0, true, true);
        }

        // ---- SELECT ----
        String selectSql;
        List<Object> selectParams = new ArrayList<>();
        if (hasStatusFilter) {
            selectSql = """
                    SELECT record_id, phone, first_name, last_name,
                           custom_fields::text, status, disposition_code, created_at,
                           attempt_count, next_attempt_at, last_contact_id
                    FROM campaign_contact
                    WHERE campaign_id = ?::uuid
                      AND tenant_id = ?::uuid
                      AND status = ?
                    ORDER BY created_at DESC
                    LIMIT ? OFFSET ?
                    """;
            selectParams.add(campaignId.toString());
            selectParams.add(tenantId.toString());
            selectParams.add(statusFilter);
        } else {
            selectSql = """
                    SELECT record_id, phone, first_name, last_name,
                           custom_fields::text, status, disposition_code, created_at,
                           attempt_count, next_attempt_at, last_contact_id
                    FROM campaign_contact
                    WHERE campaign_id = ?::uuid
                      AND tenant_id = ?::uuid
                    ORDER BY created_at DESC
                    LIMIT ? OFFSET ?
                    """;
            selectParams.add(campaignId.toString());
            selectParams.add(tenantId.toString());
        }
        selectParams.add(size);
        selectParams.add((long) page * size);

        List<CampaignContactResponse> content = jdbcTemplate.query(
                selectSql,
                selectParams.toArray(),
                (rs, rowNum) -> {
                    UUID recordId = UUID.fromString(rs.getString("record_id"));
                    String phone = rs.getString("phone");
                    String firstName = rs.getString("first_name");
                    String lastName = rs.getString("last_name");
                    String customFieldsJson = rs.getString("custom_fields");
                    String status = rs.getString("status");
                    String errorMessage = rs.getString("disposition_code");
                    Timestamp createdAtTs = rs.getTimestamp("created_at");
                    Instant createdAt = createdAtTs != null ? createdAtTs.toInstant() : null;

                    Map<String, String> customFields = parseCustomFields(customFieldsJson);

                    int attemptCount = rs.getInt("attempt_count");
                    Timestamp nextAttemptAtTs = rs.getTimestamp("next_attempt_at");
                    Instant nextAttemptAt = nextAttemptAtTs != null ? nextAttemptAtTs.toInstant() : null;

                    String lastContactIdStr = rs.getString("last_contact_id");
                    UUID lastContactId = lastContactIdStr != null ? UUID.fromString(lastContactIdStr) : null;

                    return new CampaignContactResponse(
                            recordId,
                            phone,
                            firstName,
                            lastName,
                            customFields,
                            status,
                            errorMessage,
                            createdAt,
                            attemptCount,
                            nextAttemptAt,
                            lastContactId
                    );
                }
        );

        int totalPages = (int) Math.ceil((double) totalElements / size);
        boolean isFirst = page == 0;
        boolean isLast = page >= totalPages - 1;

        log.debug("[CampaignContactRepo] findByCampaign: campaignId={}, status={}, page={}, size={}, " +
                        "totalElements={}, zwrócono={}",
                campaignId, statusFilter, page, size, totalElements, content.size());

        return new PagedResponse<>(content, page, size, totalElements, totalPages, isFirst, isLast);
    }

    /**
     * Parsuje JSON string z kolumny {@code custom_fields} na mapę String->String.
     *
     * <p>Zwraca {@code null} gdy wejście jest puste lub nie jest poprawnym JSON.
     *
     * @param json JSON string z bazy (może być {@code null}, {@code "{}"} lub {@code "{\"k\":\"v\",...}"})
     * @return mapa pól lub {@code null}
     */
    // =========================================================================
    // BE-085: Aktualizacja last_contact_id po wydzwonieniu przez dialer
    // =========================================================================

    /**
     * Ustawia {@code last_contact_id} na rekordzie campaign_contact po wydzwonieniu przez dialer.
     *
     * <p>Wywoływane przez {@code ProgressiveDialerService} po pomyślnym inicjowaniu połączenia,
     * umożliwiając szybki dostęp do ostatniego kontaktu związanego z rekordem.
     *
     * @param recordId   UUID rekordu campaign_contact
     * @param campaignId UUID kampanii
     * @param contactId  UUID nowo utworzonego kontaktu
     */
    @Transactional
    public void updateLastContactId(UUID recordId, UUID campaignId, UUID contactId) {
        setTenantContextInDb(TenantContext.getTenantId());

        jdbcTemplate.update(
                """
                UPDATE campaign_contact
                   SET last_contact_id = ?::uuid,
                       updated_at = NOW()
                 WHERE record_id   = ?::uuid
                   AND campaign_id = ?::uuid
                """,
                contactId.toString(),
                recordId.toString(),
                campaignId.toString()
        );

        log.debug("[CampaignContactRepo] updateLastContactId: recordId={}, contactId={}", recordId, contactId);
    }

    private Map<String, String> parseCustomFields(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return null;
        }
        try {
            Map<String, String> result = objectMapper.readValue(json, new TypeReference<>() {});
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            log.warn("[CampaignContactRepo] Nie można sparsować custom_fields: json='{}', błąd={}", json, e.getMessage());
            return null;
        }
    }
}
