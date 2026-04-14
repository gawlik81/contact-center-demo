package com.contactcenter.domain.repository;

import com.contactcenter.domain.model.PhoneRoutingRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repozytorium dla tabeli {@code phone_routing_rule}.
 *
 * <p>Rozszerza {@link TenantAwareRepository} – wszystkie zapytania wymagają
 * ustawienia kontekstu RLS przez {@code setTenantContextInDb(tenantId)}.
 *
 * <p>Kluczowe aspekty implementacji:
 * <ul>
 *   <li>Kolumna {@code days_of_week} jest typem {@code integer[]} w PostgreSQL –
 *       przekazywana jako tekst w formacie {@code {1,2,3}} przy native query.</li>
 *   <li>Wykrywanie kolizji używa operatora {@code &&} (array overlap) i zakresu czasowego.</li>
 * </ul>
 */
@Slf4j
@Repository
public class PhoneRoutingRuleRepository extends TenantAwareRepository {

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Pobiera wszystkie reguły dla danego numeru telefonu w tenantcie,
     * posortowane rosnąco po {@code time_start}.
     *
     * @param phoneNumberId UUID numeru telefonu
     * @param tenantId      UUID tenanta
     * @return lista reguł posortowanych po {@code time_start}
     */
    @Transactional(readOnly = true)
    public List<PhoneRoutingRule> findByPhoneNumberIdAndTenantId(UUID phoneNumberId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        @SuppressWarnings("unchecked")
        List<PhoneRoutingRule> results = em.createNativeQuery(
                        """
                        SELECT * FROM phone_routing_rule
                        WHERE phone_number_id = CAST(:phoneNumberId AS uuid)
                          AND tenant_id       = CAST(:tenantId AS uuid)
                        ORDER BY time_start ASC
                        """,
                        PhoneRoutingRule.class)
                .setParameter("phoneNumberId", phoneNumberId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getResultList();

        log.debug("[PhoneRoutingRuleRepo] Lista reguł: phoneNumberId={}, znaleziono={}",
                phoneNumberId, results.size());
        return results;
    }

    /**
     * Pobiera pojedynczą regułę po ruleId z weryfikacją przynależności do numeru i tenanta.
     *
     * @param ruleId        UUID reguły
     * @param phoneNumberId UUID numeru telefonu (weryfikacja przynależności)
     * @param tenantId      UUID tenanta
     * @return Optional z regułą lub empty gdy nie istnieje / niezgodny tenant/numer
     */
    @Transactional(readOnly = true)
    public Optional<PhoneRoutingRule> findByRuleIdAndPhoneNumberIdAndTenantId(
            UUID ruleId, UUID phoneNumberId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        PhoneRoutingRule rule = em.find(PhoneRoutingRule.class, ruleId);
        if (rule == null
                || !rule.getTenantId().equals(tenantId)
                || !rule.getPhoneNumberId().equals(phoneNumberId)) {
            return Optional.empty();
        }
        return Optional.of(rule);
    }

    /**
     * Sprawdza czy dla podanego numeru istnieje co najmniej jedna aktywna reguła routingu.
     *
     * <p>Używane przy próbie usunięcia numeru telefonu – jeśli istnieją aktywne reguły,
     * usunięcie jest blokowane (HTTP 409).
     *
     * @param phoneNumberId UUID numeru telefonu
     * @param tenantId      UUID tenanta
     * @return {@code true} gdy istnieje co najmniej jedna aktywna reguła
     */
    @Transactional(readOnly = true)
    public boolean existsActiveRulesByPhoneNumberId(UUID phoneNumberId, UUID tenantId) {
        setTenantContextInDb(tenantId);

        Number count = (Number) em.createNativeQuery(
                        """
                        SELECT COUNT(*) FROM phone_routing_rule
                        WHERE phone_number_id = CAST(:phoneNumberId AS uuid)
                          AND tenant_id       = CAST(:tenantId AS uuid)
                          AND is_active       = TRUE
                        """)
                .setParameter("phoneNumberId", phoneNumberId.toString())
                .setParameter("tenantId", tenantId.toString())
                .getSingleResult();

        boolean hasActive = count.longValue() > 0;
        log.debug("[PhoneRoutingRuleRepo] Aktywne reguły dla numeru={}: {}", phoneNumberId, hasActive);
        return hasActive;
    }

    /**
     * Wyszukuje reguły kolidujące z podanym harmonogramem dla danego numeru telefonu.
     *
     * <p>Kolizja zachodzi gdy:
     * <ul>
     *   <li>Reguła jest aktywna ({@code is_active = TRUE})</li>
     *   <li>Pokrywają się dni tygodnia (operator {@code &&})</li>
     *   <li>Okna czasowe nakładają się: {@code time_start < :timeEnd AND time_end > :timeStart}</li>
     * </ul>
     *
     * <p>Parametr {@code excludeRuleId} wyklucza samą siebie przy UPDATE
     * (reguła nie koliduje ze sobą).
     *
     * @param phoneNumberId UUID numeru telefonu
     * @param excludeRuleId UUID reguły do wykluczenia (null przy CREATE)
     * @param daysOfWeek    lista dni tygodnia do sprawdzenia
     * @param timeStart     czas rozpoczęcia
     * @param timeEnd       czas zakończenia
     * @param tenantId      UUID tenanta (do ustawienia kontekstu RLS)
     * @return lista UUID kolidujących reguł
     */
    @Transactional(readOnly = true)
    public List<UUID> findOverlapping(
            UUID phoneNumberId,
            UUID excludeRuleId,
            List<Integer> daysOfWeek,
            LocalTime timeStart,
            LocalTime timeEnd,
            UUID tenantId) {

        setTenantContextInDb(tenantId);

        // Konwersja listy dni na format PostgreSQL ARRAY, np. {1,2,3}
        String daysArray = buildPostgresIntArray(daysOfWeek);

        @SuppressWarnings("unchecked")
        List<Object> rawIds = em.createNativeQuery(
                        """
                        SELECT r.rule_id::text FROM phone_routing_rule r
                        WHERE r.phone_number_id = CAST(:phoneNumberId AS uuid)
                          AND r.is_active       = TRUE
                          AND (CAST(:excludeRuleId AS uuid) IS NULL OR r.rule_id != CAST(:excludeRuleId AS uuid))
                          AND r.days_of_week   && CAST(:days AS integer[])
                          AND r.time_start      < CAST(:timeEnd AS time)
                          AND r.time_end        > CAST(:timeStart AS time)
                        """)
                .setParameter("phoneNumberId", phoneNumberId.toString())
                .setParameter("excludeRuleId", excludeRuleId != null ? excludeRuleId.toString() : null)
                .setParameter("days", daysArray)
                .setParameter("timeStart", timeStart.toString())
                .setParameter("timeEnd", timeEnd.toString())
                .getResultList();

        List<UUID> conflictingIds = rawIds.stream()
                .map(id -> UUID.fromString(id.toString()))
                .toList();

        log.debug("[PhoneRoutingRuleRepo] Znaleziono kolizji: phoneNumberId={}, count={}",
                phoneNumberId, conflictingIds.size());
        return conflictingIds;
    }

    // =========================================================================
    // Zapis
    // =========================================================================

    /**
     * Zapisuje lub aktualizuje regułę routingu.
     *
     * <p>Przed zapisem waliduje przynależność do tenanta ({@code assertSameTenant}).
     *
     * @param rule encja do zapisu
     * @return zapisana encja (ze wygenerowanym ID jeśli nowa)
     */
    @Transactional
    public PhoneRoutingRule save(PhoneRoutingRule rule) {
        assertSameTenant(rule.getTenantId());
        setTenantContextInDb(rule.getTenantId());

        if (rule.getRuleId() == null) {
            em.persist(rule);
            log.info("[PhoneRoutingRuleRepo] Utworzono regułę: phoneNumberId={}, tenant={}",
                    rule.getPhoneNumberId(), rule.getTenantId());
            return rule;
        }

        PhoneRoutingRule merged = em.merge(rule);
        log.debug("[PhoneRoutingRuleRepo] Zaktualizowano regułę: ruleId={}, tenant={}",
                merged.getRuleId(), merged.getTenantId());
        return merged;
    }

    /**
     * Usuwa regułę routingu (hard delete).
     *
     * @param rule encja do usunięcia
     */
    @Transactional
    public void delete(PhoneRoutingRule rule) {
        assertSameTenant(rule.getTenantId());
        setTenantContextInDb(rule.getTenantId());

        PhoneRoutingRule managed = em.contains(rule) ? rule : em.merge(rule);
        em.remove(managed);
        log.info("[PhoneRoutingRuleRepo] Usunięto regułę: ruleId={}, tenant={}",
                rule.getRuleId(), rule.getTenantId());
    }

    // =========================================================================
    // Pomocnicze
    // =========================================================================

    /**
     * Konwertuje listę liczb całkowitych na format literału tablicy PostgreSQL, np. {@code {1,2,3}}.
     *
     * <p>Format wymagany przy przekazywaniu {@code CAST(:days AS integer[])} w native query.
     */
    private String buildPostgresIntArray(List<Integer> days) {
        return "{" + days.stream()
                .map(String::valueOf)
                .reduce((a, b) -> a + "," + b)
                .orElse("") + "}";
    }
}
