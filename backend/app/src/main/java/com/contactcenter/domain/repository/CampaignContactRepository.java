package com.contactcenter.domain.repository;

import com.contactcenter.api.PagedResponse;
import com.contactcenter.api.campaign.dto.CampaignContactResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
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
public class CampaignContactRepository extends TenantAwareRepository {

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
        jdbcTemplate.execute(
                "SELECT set_tenant_context('" + tenantId + "'::uuid)"
        );

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
        jdbcTemplate.execute(
                "SELECT set_tenant_context('" + tenantId + "'::uuid)"
        );

        List<String> phones = jdbcTemplate.queryForList(
                "SELECT phone FROM campaign_contact WHERE campaign_id = ?::uuid AND tenant_id = ?::uuid AND phone IS NOT NULL",
                String.class,
                campaignId.toString(),
                tenantId.toString()
        );

        return new java.util.HashSet<>(phones);
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
        jdbcTemplate.execute(
                "SELECT set_tenant_context('" + tenantId + "'::uuid)"
        );

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
                           custom_fields::text, status, disposition_code, created_at
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
                           custom_fields::text, status, disposition_code, created_at
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

                    return new CampaignContactResponse(
                            recordId,
                            phone,
                            firstName,
                            lastName,
                            customFields,
                            status,
                            errorMessage,
                            createdAt
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
