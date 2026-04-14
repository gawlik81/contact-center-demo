package com.contactcenter.domain.service;

import com.contactcenter.api.phonenumber.dto.CreatePhoneRoutingRuleRequest;
import com.contactcenter.api.phonenumber.dto.PhoneRoutingRuleResponse;
import com.contactcenter.api.phonenumber.dto.UpdatePhoneRoutingRuleRequest;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.exception.RoutingRuleConflictException;
import com.contactcenter.domain.model.PhoneNumber;
import com.contactcenter.domain.model.PhoneRoutingRule;
import com.contactcenter.domain.repository.PhoneNumberRepository;
import com.contactcenter.domain.repository.PhoneRoutingRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Serwis domenowy zarządzający regułami routingu dla numerów telefonów.
 *
 * <p>Implementuje logikę biznesową BE-034:
 * <ul>
 *   <li>Walidacja cross-field DTO (timeEnd > timeStart, XOR target)</li>
 *   <li>Wykrywanie kolizji harmonogramów PRZED zapisem (HTTP 409 z listą ruleId)</li>
 *   <li>CRUD: tworzenie, lista, aktualizacja (PATCH), hard delete</li>
 * </ul>
 *
 * <p>Kolizje reguł wykrywane są aplikacyjnie w {@link #assertNoOverlap} przed wywołaniem
 * DB. Trigger {@code trg_routing_rule_collision} działa jako dodatkowe zabezpieczenie
 * i jego wyjątek obsługiwany jest w {@code GlobalExceptionHandler}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhoneRoutingRuleService {

    private final PhoneRoutingRuleRepository phoneRoutingRuleRepository;
    private final PhoneNumberRepository phoneNumberRepository;

    // =========================================================================
    // Tworzenie
    // =========================================================================

    /**
     * Tworzy nową regułę routingu dla numeru telefonu.
     *
     * @param tenantId      UUID tenanta (z TenantContext / kontrolera)
     * @param phoneNumberId UUID numeru telefonu
     * @param request       dane nowej reguły
     * @return DTO nowo utworzonej reguły
     * @throws ResourceNotFoundException    gdy numer telefonu nie istnieje (HTTP 404)
     * @throws IllegalArgumentException     gdy walidacja cross-field się nie powiedzie (HTTP 422)
     * @throws RoutingRuleConflictException gdy reguła koliduje z istniejącymi (HTTP 409)
     */
    @Transactional
    public PhoneRoutingRuleResponse createRule(UUID tenantId, UUID phoneNumberId,
                                               CreatePhoneRoutingRuleRequest request) {
        validateCreateRequest(request);
        PhoneNumber phoneNumber = requirePhoneNumber(tenantId, phoneNumberId);

        assertNoOverlap(phoneNumberId, null,
                request.daysOfWeek(), request.timeStart(), request.timeEnd(), tenantId);

        PhoneRoutingRule rule = PhoneRoutingRule.builder()
                .tenantId(phoneNumber.getTenantId())
                .phoneNumberId(phoneNumberId)
                .ivrTreeId(request.ivrTreeId())
                .queueId(request.queueId())
                .daysOfWeek(request.daysOfWeek())
                .timeStart(request.timeStart())
                .timeEnd(request.timeEnd())
                .active(request.isActive() != null ? request.isActive() : true)
                .build();

        PhoneRoutingRule saved = phoneRoutingRuleRepository.save(rule);
        log.info("[PhoneRoutingRuleService] Utworzono regułę: ruleId={}, phoneNumberId={}, tenant={}",
                saved.getRuleId(), phoneNumberId, tenantId);

        return PhoneRoutingRuleResponse.from(saved);
    }

    // =========================================================================
    // Odczyt
    // =========================================================================

    /**
     * Zwraca listę reguł routingu dla numeru telefonu, posortowaną po {@code time_start}.
     *
     * @param tenantId      UUID tenanta
     * @param phoneNumberId UUID numeru telefonu
     * @return lista reguł
     * @throws ResourceNotFoundException gdy numer telefonu nie istnieje (HTTP 404)
     */
    @Transactional(readOnly = true)
    public List<PhoneRoutingRuleResponse> listRules(UUID tenantId, UUID phoneNumberId) {
        requirePhoneNumber(tenantId, phoneNumberId);

        return phoneRoutingRuleRepository
                .findByPhoneNumberIdAndTenantId(phoneNumberId, tenantId)
                .stream()
                .map(PhoneRoutingRuleResponse::from)
                .toList();
    }

    // =========================================================================
    // Aktualizacja
    // =========================================================================

    /**
     * Częściowa aktualizacja reguły routingu (PATCH semantics).
     *
     * <p>Tylko pola != null są nadpisywane. Walidacja cross-field przeprowadzana
     * na wynikowym stanie (merge istniejące + nowe wartości).
     *
     * @param tenantId      UUID tenanta
     * @param phoneNumberId UUID numeru telefonu
     * @param ruleId        UUID reguły
     * @param request       pola do aktualizacji
     * @return DTO zaktualizowanej reguły
     * @throws ResourceNotFoundException    gdy reguła lub numer nie istnieje (HTTP 404)
     * @throws IllegalArgumentException     gdy walidacja cross-field się nie powiedzie (HTTP 422)
     * @throws RoutingRuleConflictException gdy reguła koliduje z innymi (HTTP 409)
     */
    @Transactional
    public PhoneRoutingRuleResponse updateRule(UUID tenantId, UUID phoneNumberId,
                                               UUID ruleId, UpdatePhoneRoutingRuleRequest request) {
        requirePhoneNumber(tenantId, phoneNumberId);

        PhoneRoutingRule rule = phoneRoutingRuleRepository
                .findByRuleIdAndPhoneNumberIdAndTenantId(ruleId, phoneNumberId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reguła routingu nie istnieje: " + ruleId));

        // Merge pól – null = brak zmiany; walidacja XOR target przed dalszą logiką
        resolveTarget(rule, request);
        List<Integer> daysOfWeek  = request.daysOfWeek() != null ? request.daysOfWeek() : rule.getDaysOfWeek();
        LocalTime timeStart       = request.timeStart() != null ? request.timeStart() : rule.getTimeStart();
        LocalTime timeEnd         = request.timeEnd()   != null ? request.timeEnd()   : rule.getTimeEnd();

        // Cross-field walidacja na wynikowym stanie
        if (!timeEnd.isAfter(timeStart)) {
            throw new IllegalArgumentException(
                    "timeEnd musi być późniejszy niż timeStart (timeStart=" + timeStart + ", timeEnd=" + timeEnd + ")");
        }

        // Wykrywanie kolizji z wykluczeniem samej siebie
        assertNoOverlap(phoneNumberId, ruleId, daysOfWeek, timeStart, timeEnd, tenantId);

        // Aktualizacja pól
        applyTargetToRule(rule, request);
        rule.setDaysOfWeek(daysOfWeek);
        rule.setTimeStart(timeStart);
        rule.setTimeEnd(timeEnd);
        if (request.isActive() != null) {
            rule.setActive(request.isActive());
        }

        PhoneRoutingRule updated = phoneRoutingRuleRepository.save(rule);
        log.info("[PhoneRoutingRuleService] Zaktualizowano regułę: ruleId={}, phoneNumberId={}, tenant={}",
                ruleId, phoneNumberId, tenantId);

        return PhoneRoutingRuleResponse.from(updated);
    }

    // =========================================================================
    // Usuwanie
    // =========================================================================

    /**
     * Hard delete reguły routingu.
     *
     * @param tenantId      UUID tenanta
     * @param phoneNumberId UUID numeru telefonu
     * @param ruleId        UUID reguły
     * @throws ResourceNotFoundException gdy reguła lub numer nie istnieje (HTTP 404)
     */
    @Transactional
    public void deleteRule(UUID tenantId, UUID phoneNumberId, UUID ruleId) {
        requirePhoneNumber(tenantId, phoneNumberId);

        PhoneRoutingRule rule = phoneRoutingRuleRepository
                .findByRuleIdAndPhoneNumberIdAndTenantId(ruleId, phoneNumberId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Reguła routingu nie istnieje: " + ruleId));

        phoneRoutingRuleRepository.delete(rule);
        log.info("[PhoneRoutingRuleService] Usunięto regułę: ruleId={}, phoneNumberId={}, tenant={}",
                ruleId, phoneNumberId, tenantId);
    }

    // =========================================================================
    // Prywatne metody pomocnicze
    // =========================================================================

    /**
     * Pobiera numer telefonu lub rzuca 404.
     */
    private PhoneNumber requirePhoneNumber(UUID tenantId, UUID phoneNumberId) {
        return phoneNumberRepository.findById(phoneNumberId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Numer telefonu nie istnieje: " + phoneNumberId));
    }

    /**
     * Sprawdza kolizje harmonogramów; jeśli znajdzie – rzuca {@link RoutingRuleConflictException}.
     */
    private void assertNoOverlap(UUID phoneNumberId, UUID excludeRuleId,
                                  List<Integer> daysOfWeek, LocalTime timeStart, LocalTime timeEnd,
                                  UUID tenantId) {
        List<UUID> conflicts = phoneRoutingRuleRepository.findOverlapping(
                phoneNumberId, excludeRuleId, daysOfWeek, timeStart, timeEnd, tenantId);

        if (!conflicts.isEmpty()) {
            log.warn("[PhoneRoutingRuleService] Kolizja reguł: phoneNumberId={}, kolizje={}",
                    phoneNumberId, conflicts);
            throw new RoutingRuleConflictException(conflicts);
        }
    }

    /**
     * Walidacja cross-field dla CreatePhoneRoutingRuleRequest.
     */
    private void validateCreateRequest(CreatePhoneRoutingRuleRequest request) {
        if (!request.isTimeRangeValid()) {
            throw new IllegalArgumentException(
                    "timeEnd musi być późniejszy niż timeStart (timeStart="
                    + request.timeStart() + ", timeEnd=" + request.timeEnd() + ")");
        }
        if (!request.isExactlyOneTargetSet()) {
            throw new IllegalArgumentException(
                    "Dokładnie jeden z ivrTreeId / queueId musi być podany (nie oba, nie żaden)");
        }
    }

    /**
     * Rozwiązuje cel routingu dla UPDATE – zwraca listę pomocniczą (używana tylko jako walidacja).
     * Faktyczna aktualizacja pól odbywa się w {@link #applyTargetToRule}.
     */
    private List<UUID> resolveTarget(PhoneRoutingRule existing, UpdatePhoneRoutingRuleRequest request) {
        // Jeśli request zmienia cel – walidujemy XOR
        boolean changesTarget = request.ivrTreeId() != null || request.queueId() != null;
        if (changesTarget) {
            UUID newIvr   = request.ivrTreeId() != null ? request.ivrTreeId() : null;
            UUID newQueue = request.queueId()   != null ? request.queueId()   : null;
            boolean ivrSet   = newIvr   != null;
            boolean queueSet = newQueue != null;
            if (ivrSet == queueSet) {
                throw new IllegalArgumentException(
                        "Dokładnie jeden z ivrTreeId / queueId musi być podany (nie oba, nie żaden)");
            }
        }
        return List.of(); // placeholder – realna logika w applyTargetToRule
    }

    /**
     * Aplikuje zmiany celu routingu na encji (obsługuje XOR: ivrTreeId vs queueId).
     */
    private void applyTargetToRule(PhoneRoutingRule rule, UpdatePhoneRoutingRuleRequest request) {
        if (request.ivrTreeId() != null) {
            rule.setIvrTreeId(request.ivrTreeId());
            rule.setQueueId(null);
        } else if (request.queueId() != null) {
            rule.setQueueId(request.queueId());
            rule.setIvrTreeId(null);
        }
        // null = brak zmiany – nie dotykamy istniejących wartości
    }
}
