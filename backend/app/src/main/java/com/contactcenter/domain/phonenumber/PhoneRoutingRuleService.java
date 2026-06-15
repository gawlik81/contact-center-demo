package com.contactcenter.domain.phonenumber;

import com.contactcenter.api.phonenumber.dto.CreatePhoneRoutingRuleRequest;
import com.contactcenter.api.phonenumber.dto.PhoneRoutingRuleResponse;
import com.contactcenter.api.phonenumber.dto.UpdatePhoneRoutingRuleRequest;
import com.contactcenter.domain.exception.ResourceNotFoundException;
import com.contactcenter.domain.exception.RoutingRuleConflictException;

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
 * <p>Kolizje reguł wykrywane są aplikacyjnie przed wywołaniem DB. Trigger
 * {@code trg_routing_rule_collision} działa jako dodatkowe zabezpieczenie i jego wyjątek
 * obsługiwany jest w {@code GlobalExceptionHandler}.
 */
public interface PhoneRoutingRuleService {

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
    PhoneRoutingRuleResponse createRule(UUID tenantId, UUID phoneNumberId, CreatePhoneRoutingRuleRequest request);

    /**
     * Zwraca listę reguł routingu dla numeru telefonu, posortowaną po {@code time_start}.
     *
     * @param tenantId      UUID tenanta
     * @param phoneNumberId UUID numeru telefonu
     * @return lista reguł
     * @throws ResourceNotFoundException gdy numer telefonu nie istnieje (HTTP 404)
     */
    List<PhoneRoutingRuleResponse> listRules(UUID tenantId, UUID phoneNumberId);

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
    PhoneRoutingRuleResponse updateRule(UUID tenantId, UUID phoneNumberId, UUID ruleId,
                                         UpdatePhoneRoutingRuleRequest request);

    /**
     * Hard delete reguły routingu.
     *
     * @param tenantId      UUID tenanta
     * @param phoneNumberId UUID numeru telefonu
     * @param ruleId        UUID reguły
     * @throws ResourceNotFoundException gdy reguła lub numer nie istnieje (HTTP 404)
     */
    void deleteRule(UUID tenantId, UUID phoneNumberId, UUID ruleId);

    /**
     * Zwraca reguły routingu przypisane do numeru telefonu (bez filtrowania po aktywności/harmonogramie).
     *
     * <p>Używane przez silnik routingu połączeń przychodzących ({@code IncomingCallRoutingService}),
     * który samodzielnie filtruje wynik po {@code isActive()}, dniach tygodnia i godzinach.
     *
     * @param tenantId      UUID tenanta
     * @param phoneNumberId UUID numeru telefonu
     * @return lista reguł posortowanych po {@code time_start} ASC
     */
    List<PhoneRoutingRule> findActiveRulesForPhoneNumber(UUID tenantId, UUID phoneNumberId);

    /**
     * Sprawdza czy drzewo IVR jest przypisane do co najmniej jednej reguły routingu.
     *
     * <p>Używane przy deaktywacji drzewa IVR ({@code IvrService}) – jeśli istnieje reguła
     * (aktywna lub nie) wskazująca na to drzewo, deaktywacja jest blokowana (HTTP 409).
     *
     * @param tenantId  UUID tenanta
     * @param ivrTreeId UUID drzewa IVR
     * @return {@code true} gdy istnieje co najmniej jedna reguła wskazująca na podane drzewo
     */
    boolean existsRulesByIvrTreeId(UUID tenantId, UUID ivrTreeId);
}
