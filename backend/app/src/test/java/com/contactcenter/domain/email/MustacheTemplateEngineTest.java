package com.contactcenter.domain.email;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Testy jednostkowe dla {@link MustacheTemplateEngine}.
 *
 * <p>Weryfikuje:
 * <ul>
 *   <li>Podstawianie zmiennych w szablonie Mustache</li>
 *   <li>Obsługę szablonów bez zmiennych</li>
 *   <li>Wykrywanie brakujących zmiennych</li>
 *   <li>Parsowanie nazw zmiennych z szablonu</li>
 * </ul>
 */
@DisplayName("MustacheTemplateEngine – renderowanie szablonów Mustache")
class MustacheTemplateEngineTest {

    private MustacheTemplateEngine engine;

    @BeforeEach
    void setUp() {
        engine = new MustacheTemplateEngine();
    }

    // =========================================================================
    // Renderowanie
    // =========================================================================

    @Nested
    @DisplayName("render() – podstawianie zmiennych")
    class Render {

        @Test
        @DisplayName("Powinien podstawić zmienną w tekście")
        void shouldReplaceVariable() {
            // given
            String template = "Witaj {{customerName}}!";
            Map<String, Object> variables = Map.of("customerName", "Jan Kowalski");

            // when
            String result = engine.render(template, variables);

            // then
            assertThat(result).isEqualTo("Witaj Jan Kowalski!");
        }

        @Test
        @DisplayName("Powinien podstawić wiele zmiennych")
        void shouldReplaceMultipleVariables() {
            // given
            String template = "<p>Drogi/a {{customerName}}, Twój numer sprawy to {{ticketNumber}}.</p>";
            Map<String, Object> variables = Map.of(
                    "customerName", "Anna Nowak",
                    "ticketNumber", "TKT-12345"
            );

            // when
            String result = engine.render(template, variables);

            // then
            assertThat(result).contains("Anna Nowak");
            assertThat(result).contains("TKT-12345");
        }

        @Test
        @DisplayName("Powinien zwrócić tekst bez zmian gdy brak placeholderów")
        void shouldReturnUnchanged_whenNoPlaceholders() {
            // given
            String template = "<p>Stała treść bez zmiennych.</p>";

            // when
            String result = engine.render(template, Map.of());

            // then
            assertThat(result).isEqualTo("<p>Stała treść bez zmiennych.</p>");
        }

        @Test
        @DisplayName("Powinien zwrócić pusty string dla null szablonu")
        void shouldReturnEmpty_whenTemplateIsNull() {
            // when
            String result = engine.render(null, Map.of());

            // then
            assertThat(result).isEqualTo("");
        }

        @Test
        @DisplayName("Powinien zwrócić pusty string dla pustego szablonu")
        void shouldReturnEmpty_whenTemplateIsBlank() {
            // when
            String result = engine.render("   ", Map.of());

            // then
            assertThat(result).isEqualTo("");
        }

        @Test
        @DisplayName("Powinien pozostawić pusty string gdy zmienna jest niezdefiniowana (Mustache default)")
        void shouldRenderEmpty_whenVariableUndefined() {
            // given – Mustache domyślnie ignoruje niezdefiniowane zmienne (renderuje "")
            String template = "Witaj {{undefinedVar}}!";

            // when
            String result = engine.render(template, Map.of());

            // then – Mustache renderuje pusty string dla brakujących zmiennych
            assertThat(result).isEqualTo("Witaj !");
        }

        @Test
        @DisplayName("Test regresyjny: JMustache natywnie obsługuje zagnieżdżony dostęp przez kropkę "
                + "do obiektów Map w kontekście ({{obj.klucz}}) – patrz customerCustomFields")
        void shouldResolveNestedDotNotation_whenValueIsNestedMap() {
            // given – zagnieżdżona mapa jako wartość zmiennej (wzorzec customerCustomFields)
            String template = "Status VIP: {{customerCustomFields.vip}}, segment: {{customerCustomFields.segment}}";
            Map<String, Object> variables = Map.of(
                    "customerCustomFields", Map.of("vip", "true", "segment", "gold"));

            // when
            String result = engine.render(template, variables);

            // then
            assertThat(result).isEqualTo("Status VIP: true, segment: gold");
        }

        @Test
        @DisplayName("Powinien renderować pusty string dla zagnieżdżonego klucza, "
                + "gdy root istnieje ale zagnieżdżony klucz nie")
        void shouldRenderEmpty_whenNestedKeyMissingButRootPresent() {
            // given
            String template = "Wartość: {{customerCustomFields.nieznanyKlucz}}";
            Map<String, Object> variables = Map.of("customerCustomFields", Map.of("vip", "true"));

            // when
            String result = engine.render(template, variables);

            // then
            assertThat(result).isEqualTo("Wartość: ");
        }
    }

    // =========================================================================
    // Wykrywanie brakujących zmiennych
    // =========================================================================

    @Nested
    @DisplayName("findMissingVariables() – wykrywanie brakujących zmiennych")
    class FindMissingVariables {

        @Test
        @DisplayName("Powinien zwrócić pustą listę gdy wszystkie zmienne są dostarczone")
        void shouldReturnEmpty_whenAllVariablesProvided() {
            // given
            String template = "Witaj {{name}}, Twój nr to {{ticketId}}.";
            Map<String, Object> variables = Map.of("name", "Jan", "ticketId", "123");

            // when
            List<String> missing = engine.findMissingVariables(template, variables);

            // then
            assertThat(missing).isEmpty();
        }

        @Test
        @DisplayName("Powinien zwrócić brakujące zmienne")
        void shouldReturnMissingVariables() {
            // given
            String template = "Witaj {{customerName}}, nr sprawy: {{ticketNumber}}.";
            Map<String, Object> variables = Map.of("customerName", "Jan"); // brak ticketNumber

            // when
            List<String> missing = engine.findMissingVariables(template, variables);

            // then
            assertThat(missing).containsExactly("ticketNumber");
        }

        @Test
        @DisplayName("Powinien zwrócić wszystkie brakujące zmienne")
        void shouldReturnAllMissingVariables() {
            // given
            String template = "{{a}} {{b}} {{c}}";
            Map<String, Object> variables = Map.of(); // brak wszystkich

            // when
            List<String> missing = engine.findMissingVariables(template, variables);

            // then
            assertThat(missing).containsExactlyInAnyOrder("a", "b", "c");
        }

        @Test
        @DisplayName("Powinien akceptować null jako wartość zmiennej (klucz istnieje)")
        void shouldAcceptNullValue_whenKeyExists() {
            // given
            String template = "Witaj {{customerName}}!";
            Map<String, Object> variables = new java.util.HashMap<>();
            variables.put("customerName", null); // null jest poprawną wartością

            // when
            List<String> missing = engine.findMissingVariables(template, variables);

            // then
            assertThat(missing).isEmpty();
        }

        @Test
        @DisplayName("Powinien zwrócić pustą listę dla szablonu bez zmiennych")
        void shouldReturnEmpty_whenTemplateHasNoVariables() {
            // given
            String template = "<p>Stała treść bez zmiennych.</p>";

            // when
            List<String> missing = engine.findMissingVariables(template, Map.of());

            // then
            assertThat(missing).isEmpty();
        }
    }

    // =========================================================================
    // Parsowanie nazw zmiennych
    // =========================================================================

    @Nested
    @DisplayName("extractVariableNames() – parsowanie nazw zmiennych")
    class ExtractVariableNames {

        @Test
        @DisplayName("Powinien wyodrębnić nazwy zmiennych z szablonu")
        void shouldExtractVariableNames() {
            // given
            String template = "{{firstName}} {{lastName}}, nr: {{ticketId}}";

            // when
            List<String> names = engine.extractVariableNames(template);

            // then
            assertThat(names).containsExactlyInAnyOrder("firstName", "lastName", "ticketId");
        }

        @Test
        @DisplayName("Powinien usunąć duplikaty zmiennych")
        void shouldDeduplicateVariableNames() {
            // given
            String template = "{{name}} {{name}} {{name}}";

            // when
            List<String> names = engine.extractVariableNames(template);

            // then
            assertThat(names).containsExactly("name");
            assertThat(names).hasSize(1);
        }

        @Test
        @DisplayName("Powinien zwrócić pustą listę dla szablonu bez zmiennych")
        void shouldReturnEmpty_whenNoVariables() {
            // given
            String template = "<p>Stała treść</p>";

            // when
            List<String> names = engine.extractVariableNames(template);

            // then
            assertThat(names).isEmpty();
        }

        @Test
        @DisplayName("Powinien ignorować sekcje Mustache (#, /, ^)")
        void shouldIgnoreSections() {
            // given – sekcje Mustache nie są zmiennymi prostymi
            String template = "{{#hasItems}}Są elementy{{/hasItems}}{{^empty}}Nie puste{{/empty}}{{varName}}";

            // when
            List<String> names = engine.extractVariableNames(template);

            // then – tylko zwykłe zmienne, nie sekcje
            assertThat(names).doesNotContain("hasItems", "empty");
            assertThat(names).contains("varName");
        }

        @Test
        @DisplayName("Powinien zwrócić pustą listę dla null szablonu")
        void shouldReturnEmpty_whenTemplateIsNull() {
            // when
            List<String> names = engine.extractVariableNames(null);

            // then
            assertThat(names).isEmpty();
        }
    }
}
