package com.contactcenter.api.plugin.dto;

import java.util.List;

/**
 * DTO odpowiedzi HTTP 400 zwracanej, gdy upload JAR-a pluginu zostanie odrzucony przez
 * {@code PluginValidationService} (status {@code REJECTED}).
 *
 * <p>JAR <strong>nie</strong> jest zapisywany do object storage ani do bazy danych w tym
 * przypadku — opisowe przyczyny odrzucenia trafiają wyłącznie do {@code validationErrors}.
 *
 * @param validationErrors opisowe błędy z pipeline'u walidacji (ARCHITECTURE.md §11.4)
 */
public record PluginValidationErrorResponse(List<String> validationErrors) {
}
