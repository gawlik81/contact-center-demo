package com.contactcenter.domain.exception;

import java.util.List;
import java.util.UUID;

/**
 * Wyjątek rzucany gdy nowa lub modyfikowana reguła routingu koliduje z istniejącymi regułami.
 *
 * <p>Kolizja zachodzi gdy dwie aktywne reguły dla tego samego numeru telefonu
 * mają nakładające się dni tygodnia i okna czasowe.
 *
 * <p>Mapowany na HTTP 409 Conflict przez {@code GlobalExceptionHandler}.
 * Odpowiedź zawiera listę {@code collidingRuleIds} – UUID kolidujących reguł.
 */
public class RoutingRuleConflictException extends RuntimeException {

    private final List<UUID> collidingRuleIds;

    public RoutingRuleConflictException(List<UUID> collidingRuleIds) {
        super("Reguła routingu koliduje z " + collidingRuleIds.size()
              + " istniejącą regułą: " + collidingRuleIds);
        this.collidingRuleIds = collidingRuleIds;
    }

    public List<UUID> getCollidingRuleIds() {
        return collidingRuleIds;
    }
}
