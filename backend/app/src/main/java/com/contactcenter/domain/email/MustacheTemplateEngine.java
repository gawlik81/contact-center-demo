package com.contactcenter.domain.email;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Silnik renderowania szablonów Mustache.
 *
 * <p>Używa biblioteki {@code com.github.spullara.mustache.java} (compiler 0.9.14).
 * Obsługuje proste placeholdery {@code {{varName}}} oraz podstawowe sekcje Mustache.
 *
 * <p>Wykrywanie brakujących zmiennych:
 * Przed renderowaniem parsuje szablon regex'em i porównuje znalezione
 * nazwy zmiennych z kluczami dostarczonej mapy kontekstu.
 *
 * <p><strong>Ograniczenie:</strong> Wykrywanie brakujących zmiennych działa na poziomie
 * statycznej analizy regex – nie obsługuje zagnieżdżonych obiektów Mustache ({{obj.field}}).
 */
@Slf4j
@Component
public class MustacheTemplateEngine {

    /**
     * Pattern dopasowujący proste placeholdery Mustache: {@code {{varName}}}.
     * Pomija sekcje ({{#section}}, {{/section}}, {{^section}}) i partial ({{>partial}}).
     */
    private static final Pattern VARIABLE_PATTERN =
            Pattern.compile("\\{\\{([^#^>/!{][^}]*)\\}\\}");

    private final MustacheFactory mustacheFactory;

    public MustacheTemplateEngine() {
        this.mustacheFactory = new DefaultMustacheFactory();
    }

    // =========================================================================
    // Renderowanie
    // =========================================================================

    /**
     * Renderuje szablon Mustache podstawiając wartości ze scope'u.
     *
     * <p>Nie rzuca wyjątku gdy zmienna jest niezdefiniowana – Mustache domyślnie
     * pomija nieznane placeholdery (renderuje pusty string). Walidację brakujących
     * zmiennych wykonuj przez {@link #findMissingVariables} przed wywołaniem tej metody.
     *
     * @param template  tekst szablonu z placeholderami {{varName}}
     * @param variables mapa wartości do podstawienia
     * @return wyrenderowany tekst
     * @throws MustacheRenderException gdy renderowanie się nie powiedzie (błąd parsowania)
     */
    public String render(String template, Map<String, Object> variables) {
        if (template == null || template.isBlank()) {
            return "";
        }

        try {
            Mustache mustache = mustacheFactory.compile(new StringReader(template), "template");
            StringWriter writer = new StringWriter();
            mustache.execute(writer, variables).flush();
            return writer.toString();
        } catch (Exception e) {
            log.error("[MustacheEngine] Błąd renderowania szablonu: {}", e.getMessage(), e);
            throw new MustacheRenderException("Błąd renderowania szablonu Mustache: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    // Walidacja zmiennych
    // =========================================================================

    /**
     * Zwraca listę nazw zmiennych {{varName}} obecnych w szablonie,
     * ale nieobecnych w mapie kontekstu.
     *
     * @param template  tekst szablonu do analizy
     * @param variables mapa dostępnych zmiennych
     * @return lista brakujących nazw zmiennych; pusta gdy wszystkie są dostarczone
     */
    public List<String> findMissingVariables(String template, Map<String, Object> variables) {
        List<String> defined = extractVariableNames(template);
        List<String> missing = new ArrayList<>();

        for (String varName : defined) {
            // Sprawdzamy obecność klucza (może być null jako poprawna wartość)
            if (!variables.containsKey(varName)) {
                missing.add(varName);
            }
        }

        return missing;
    }

    /**
     * Wyodrębnia wszystkie nazwy zmiennych z szablonu (bez duplikatów, zachowując kolejność).
     *
     * @param template tekst szablonu
     * @return lista unikalnych nazw zmiennych
     */
    public List<String> extractVariableNames(String template) {
        if (template == null || template.isBlank()) {
            return List.of();
        }

        List<String> names = new ArrayList<>();
        Matcher matcher = VARIABLE_PATTERN.matcher(template);

        while (matcher.find()) {
            String varName = matcher.group(1).trim();
            if (!varName.isBlank() && !names.contains(varName)) {
                names.add(varName);
            }
        }

        return names;
    }

    // =========================================================================
    // Wyjątek silnika renderowania
    // =========================================================================

    /** Wyjątek błędu parsowania lub renderowania szablonu (błąd wewnętrzny, nie użytkownika). */
    public static class MustacheRenderException extends RuntimeException {
        public MustacheRenderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
