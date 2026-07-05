package com.acme.contactcenter.plugin.googlelookup;

import com.contactcenter.pluginsdk.HttpEgressClient;
import com.contactcenter.pluginsdk.HttpResponse;
import com.contactcenter.pluginsdk.PluginConfig;
import com.contactcenter.pluginsdk.PluginContext;
import com.contactcenter.pluginsdk.PluginLogger;
import com.contactcenter.pluginsdk.model.ContactView;
import com.contactcenter.pluginsdk.model.CustomerView;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy {@link GoogleCustomSearchClient}, w szczególności obsługi błędu Google Custom Search
 * API — wyjątek musi zawierać body odpowiedzi (np. pole {@code reason} z JSON-a błędu), nie
 * tylko status code, żeby diagnostyka nie wymagała ręcznego odtwarzania zapytania przez curl.
 */
class GoogleCustomSearchClientTest {

    private static final String API_KEY = "test-api-key";
    private static final String SEARCH_ENGINE_ID = "test-cx";

    @Test
    void search_whenApiReturnsForbidden_throwsExceptionContainingResponseBody() {
        String errorBody = """
                {
                  "error": {
                    "code": 403,
                    "message": "The provided API key is invalid.",
                    "errors": [
                      {
                        "reason": "API_KEY_INVALID"
                      }
                    ]
                  }
                }
                """;
        PluginContext ctx = new StubPluginContext(new HttpResponse(403, Map.of(), bytes(errorBody)));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> GoogleCustomSearchClient.search(ctx, "jan kowalski", 5));

        assertTrue(ex.getMessage().contains("403"),
                "komunikat powinien zawierać status code");
        assertTrue(ex.getMessage().contains("API_KEY_INVALID"),
                "komunikat powinien zawierać body odpowiedzi z dokładnym 'reason'");
    }

    @Test
    void search_whenApiReturnsDailyLimitExceeded_throwsExceptionContainingResponseBody() {
        String errorBody = """
                {"error":{"code":429,"errors":[{"reason":"dailyLimitExceeded"}]}}
                """;
        PluginContext ctx = new StubPluginContext(new HttpResponse(429, Map.of(), bytes(errorBody)));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> GoogleCustomSearchClient.search(ctx, "jan kowalski", 5));

        assertTrue(ex.getMessage().contains("dailyLimitExceeded"),
                "komunikat powinien zawierać body odpowiedzi z dokładnym 'reason'");
    }

    @Test
    void search_whenApiReturnsOk_parsesItemsAndDoesNotThrow() {
        String okBody = """
                {
                  "items": [
                    {"title": "Jan Kowalski", "link": "https://example.com/jan", "snippet": "..."}
                  ]
                }
                """;
        PluginContext ctx = new StubPluginContext(new HttpResponse(200, Map.of(), bytes(okBody)));

        List<SearchResultItem> results = GoogleCustomSearchClient.search(ctx, "jan kowalski", 5);

        assertEquals(1, results.size());
        assertEquals("Jan Kowalski", results.get(0).title());
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Minimalny stub {@link PluginContext} zwracający z góry skonfigurowaną {@link HttpResponse}. */
    private static final class StubPluginContext implements PluginContext {

        private final HttpResponse response;

        StubPluginContext(HttpResponse response) {
            this.response = response;
        }

        @Override
        public CustomerView getCustomer(UUID customerId) {
            throw new UnsupportedOperationException("nieużywane w tym teście");
        }

        @Override
        public void updateCustomerFields(UUID customerId, Map<String, Object> customFields) {
            throw new UnsupportedOperationException("nieużywane w tym teście");
        }

        @Override
        public ContactView getContact(UUID contactId) {
            throw new UnsupportedOperationException("nieużywane w tym teście");
        }

        @Override
        public void appendContactNote(UUID contactId, String note) {
            throw new UnsupportedOperationException("nieużywane w tym teście");
        }

        @Override
        public HttpEgressClient httpClient() {
            return new HttpEgressClient() {
                @Override
                public HttpResponse get(String url, Map<String, String> headers) {
                    return response;
                }

                @Override
                public HttpResponse post(String url, Map<String, String> headers, byte[] body) {
                    throw new UnsupportedOperationException("nieużywane w tym teście");
                }
            };
        }

        @Override
        public PluginLogger logger() {
            return new PluginLogger() {
                @Override
                public void info(String message) {
                }

                @Override
                public void warn(String message) {
                }

                @Override
                public void error(String message, Throwable throwable) {
                }
            };
        }

        @Override
        public PluginConfig config() {
            return new PluginConfig() {
                @Override
                public Optional<String> get(String key) {
                    return switch (key) {
                        case "googleApiKey" -> Optional.of(API_KEY);
                        case "googleSearchEngineId" -> Optional.of(SEARCH_ENGINE_ID);
                        default -> Optional.empty();
                    };
                }

                @Override
                public String getOrDefault(String key, String defaultValue) {
                    return get(key).orElse(defaultValue);
                }
            };
        }
    }
}
