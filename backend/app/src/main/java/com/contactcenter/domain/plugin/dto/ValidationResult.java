package com.contactcenter.domain.plugin.dto;

import java.util.List;

/**
 * Wynik walidacji JAR-a pluginu zwracany przez {@code PluginValidationService.validate}.
 *
 * <p>Ten serwis (BE-098) tylko WALIDUJE i zwraca wynik — nie zapisuje nic do bazy danych
 * ani object storage. Zapis JAR-a i wstawienie wiersza {@code plugin_version} to zakres
 * BE-099 (kolejny ticket w kolejce EPIC-28).
 *
 * @param status           {@link ValidationStatus#VALIDATED} lub {@link ValidationStatus#REJECTED}
 * @param validationErrors opisowe błędy walidacji; pusta lista gdy {@code status == VALIDATED}
 */
public record ValidationResult(ValidationStatus status, List<String> validationErrors) {

    public static ValidationResult validated() {
        return new ValidationResult(ValidationStatus.VALIDATED, List.of());
    }

    public static ValidationResult rejected(String reason) {
        return new ValidationResult(ValidationStatus.REJECTED, List.of(reason));
    }

    public static ValidationResult rejected(List<String> reasons) {
        return new ValidationResult(ValidationStatus.REJECTED, List.copyOf(reasons));
    }

    public boolean isValidated() {
        return status == ValidationStatus.VALIDATED;
    }
}
