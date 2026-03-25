package com.contactcenter.domain.email;

import java.util.List;

/**
 * Wyjątek rzucany gdy szablon Mustache zawiera zmienne, które nie zostały
 * dostarczone w mapie kontekstu renderowania.
 *
 * <p>Mapowany na HTTP 422 Unprocessable Entity przez {@code GlobalExceptionHandler}.
 * Pole {@code missingVariables} wskazuje których zmiennych brakuje.
 *
 * <p>Przykład odpowiedzi HTTP:
 * <pre>
 * {
 *   "status": 422,
 *   "title": "Brakujące zmienne szablonu",
 *   "missingVariables": ["customerName", "ticketNumber"]
 * }
 * </pre>
 */
public class TemplateRenderException extends RuntimeException {

    private final List<String> missingVariables;

    public TemplateRenderException(List<String> missingVariables) {
        super("Brakujące zmienne szablonu: " + missingVariables);
        this.missingVariables = List.copyOf(missingVariables);
    }

    public TemplateRenderException(String message, List<String> missingVariables) {
        super(message);
        this.missingVariables = List.copyOf(missingVariables);
    }

    public List<String> getMissingVariables() {
        return missingVariables;
    }
}
