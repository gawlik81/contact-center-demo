package com.contactcenter.api.phonenumber.dto;

import java.util.List;
import java.util.UUID;

/**
 * DTO odpowiedzi przy kolizji reguł routingu (HTTP 409).
 *
 * <p>Zwracany gdy nowa lub modyfikowana reguła nakłada się na istniejące aktywne reguły
 * dla tego samego numeru telefonu.
 *
 * <p>Przykład JSON:
 * <pre>
 * {
 *   "collidingRuleIds": ["uuid-1", "uuid-2"],
 *   "message": "Reguła routingu koliduje z 2 istniejącą regułą"
 * }
 * </pre>
 */
public record ConflictRuleResponse(
        /** Lista UUID reguł kolidujących z nową / modyfikowaną regułą. */
        List<UUID> collidingRuleIds,

        /** Czytelny opis konfliktu. */
        String message
) {
}
