package com.contactcenter.infrastructure.websocket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BE-242 / BE-249 (N5): production requirements of the WebSocket channels, checked on the REAL
 * {@code application.yml} / {@code application-prod.yml} (a cheap stand-in for starting the whole context without the
 * variable - {@code FullApplicationContextStartupTest} proves the context DOES start with them supplied).
 */
@DisplayName("Konfiguracja prod kanalow WS: HMAC wymagany, jawny WEBSOCKET_SESSION_ENV (BE-242, BE-249 N5)")
class WebSocketProdConfigurationTest {

    private static MockEnvironment environment(boolean prod, Map<String, Object> variables) throws IOException {
        MockEnvironment env = new MockEnvironment(); // deliberately WITHOUT the real system environment
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        if (prod) {
            for (PropertySource<?> ps : loader.load("prod", new ClassPathResource("application-prod.yml"))) {
                env.getPropertySources().addLast(ps);
            }
        }
        for (PropertySource<?> ps : loader.load("base", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(ps);
        }
        env.getPropertySources().addFirst(new MapPropertySource("variables", variables));
        return env;
    }

    @Test
    @DisplayName("profil prod ustawia websocket.hmac.required=true; bazowa konfiguracja (dev/test) nie wymaga sekretu")
    void hmacIsRequiredOnlyOnProd() throws IOException {
        assertThat(environment(true, Map.of("WEBSOCKET_SESSION_ENV", "prod")).getProperty("websocket.hmac.required"))
                .isEqualTo("true");
        assertThat(environment(false, Map.of()).getProperty("websocket.hmac.required")).isEqualTo("false");
        assertThat(environment(false, Map.of()).getProperty("websocket.hmac.secret")).isNullOrEmpty();
    }

    @Test
    @DisplayName("N5: bez WEBSOCKET_SESSION_ENV konfiguracja prod nie rozwiazuje sie (start zablokowany, komunikat z nazwa zmiennej)")
    void prodRequiresExplicitSessionEnvironment() throws IOException {
        MockEnvironment withoutVariable = environment(true, Map.of());

        assertThatThrownBy(() -> withoutVariable.getProperty("websocket.session.control.environment"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WEBSOCKET_SESSION_ENV");
        assertThat(environment(true, Map.of("WEBSOCKET_SESSION_ENV", "staging-1"))
                .getProperty("websocket.session.control.environment")).isEqualTo("staging-1");
    }

    @Test
    @DisplayName("poza prod srodowisko kanalu sterujacego ma domyslna wartosc (dev/test bez dodatkowych zmiennych)")
    void nonProdKeepsDefaultSessionEnvironment() throws IOException {
        assertThat(environment(false, Map.of()).getProperty("websocket.session.control.environment"))
                .isNotBlank();
    }

    @Test
    @DisplayName("sekret i tryb przejsciowy sa czytane ze zmiennych WEBSOCKET_HMAC_*; domyslnie tryb przejsciowy wylaczony")
    void hmacVariablesAreMapped() throws IOException {
        MockEnvironment env = environment(true, Map.of("WEBSOCKET_SESSION_ENV", "prod",
                "WEBSOCKET_HMAC_SECRET", "abc", "WEBSOCKET_HMAC_PREVIOUS_SECRET", "def",
                "WEBSOCKET_HMAC_ACCEPT_UNSIGNED", "true"));

        assertThat(env.getProperty("websocket.hmac.secret")).isEqualTo("abc");
        assertThat(env.getProperty("websocket.hmac.previous-secret")).isEqualTo("def");
        assertThat(env.getProperty("websocket.hmac.accept-unsigned")).isEqualTo("true");
        assertThat(environment(true, Map.of("WEBSOCKET_SESSION_ENV", "prod")).getProperty("websocket.hmac.accept-unsigned"))
                .isEqualTo("false");
        assertThat(List.of(env.getProperty("websocket.hmac.min-secret-bytes"))).containsExactly("32");
    }
}
