package com.contactcenter.api.phonenumber.dto;

import com.contactcenter.domain.model.PhoneRoutingRule;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * DTO odpowiedzi z danymi reguły routingu dla numeru telefonu.
 *
 * <p>Mapuje encję {@link PhoneRoutingRule} na format JSON zwracany przez API.
 */
public record PhoneRoutingRuleResponse(
        UUID ruleId,
        UUID tenantId,
        UUID phoneNumberId,
        UUID ivrTreeId,
        UUID queueId,
        List<Integer> daysOfWeek,
        LocalTime timeStart,
        LocalTime timeEnd,
        boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Fabryka tworząca DTO z encji JPA.
     *
     * @param rule encja reguły routingu
     * @return zmapowane DTO odpowiedzi
     */
    public static PhoneRoutingRuleResponse from(PhoneRoutingRule rule) {
        return new PhoneRoutingRuleResponse(
                rule.getRuleId(),
                rule.getTenantId(),
                rule.getPhoneNumberId(),
                rule.getIvrTreeId(),
                rule.getQueueId(),
                rule.getDaysOfWeek(),
                rule.getTimeStart(),
                rule.getTimeEnd(),
                rule.isActive(),
                rule.getCreatedAt(),
                rule.getUpdatedAt()
        );
    }
}
