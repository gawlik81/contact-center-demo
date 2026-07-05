package com.acme.contactcenter.plugin.googlelookup;

import com.contactcenter.pluginsdk.HttpResponse;
import com.contactcenter.pluginsdk.PluginContext;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Klient Google Custom Search JSON API, wołany WYŁĄCZNIE przez
 * {@link com.contactcenter.pluginsdk.HttpEgressClient} (jedyny dozwolony kanał sieciowy tego
 * pluginu — manifest deklaruje {@code http:egress:www.googleapis.com}, wymuszane przez hosta
 * przed każdym wywołaniem, nie przez ten kod).
 *
 * <p>Wymaga dwóch wartości konfiguracyjnych tenanta, odczytywanych przez
 * {@code PluginContext#config()}: {@code googleApiKey} i {@code googleSearchEngineId}
 * (parametr {@code cx} API Google) — patrz README.md tego projektu, sekcja "Konfiguracja".
 */
final class GoogleCustomSearchClient {

    private static final String API_ENDPOINT = "https://www.googleapis.com/customsearch/v1";

    private GoogleCustomSearchClient() {
    }

    /**
     * Wykonuje wyszukiwanie i zwraca do {@code maxResults} wyników.
     *
     * @throws IllegalStateException jeśli brakuje wymaganej konfiguracji tenanta
     * @throws RuntimeException       jeśli Google API zwróci status inny niż 200 (rzucane przez
     *                                  {@link com.contactcenter.pluginsdk.HttpEgressClient} przy
     *                                  błędzie sieci, lub explicit przez tę metodę dla 4xx/5xx)
     */
    static List<SearchResultItem> search(PluginContext ctx, String query, int maxResults) {
        String apiKey = ctx.config().get("googleApiKey")
                .orElseThrow(() -> new IllegalStateException(
                        "Brak konfiguracji 'googleApiKey' dla tej instalacji"));
        String searchEngineId = ctx.config().get("googleSearchEngineId")
                .orElseThrow(() -> new IllegalStateException(
                        "Brak konfiguracji 'googleSearchEngineId' dla tej instalacji"));

        int boundedMaxResults = Math.min(Math.max(maxResults, 1), 10);
        String url = API_ENDPOINT
                + "?key=" + urlEncode(apiKey)
                + "&cx=" + urlEncode(searchEngineId)
                + "&num=" + boundedMaxResults
                + "&q=" + urlEncode(query);

        HttpResponse response = ctx.httpClient().get(url, Map.of("Accept", "application/json"));
        String body = new String(response.body(), StandardCharsets.UTF_8);
        if (response.statusCode() != 200) {
            throw new RuntimeException(
                    "Google Custom Search API zwróciło status " + response.statusCode()
                            + ", body: " + body);
        }

        return extractItems(MinimalJson.parse(body), boundedMaxResults);
    }

    private static List<SearchResultItem> extractItems(Object parsed, int maxResults) {
        List<SearchResultItem> results = new ArrayList<>();
        if (!(parsed instanceof Map<?, ?> root)) {
            return results;
        }
        // Google zwraca odpowiedź BEZ pola "items" gdy zapytanie nie ma wyników — to nie błąd.
        if (!(root.get("items") instanceof List<?> itemList)) {
            return results;
        }
        for (Object rawItem : itemList) {
            if (results.size() >= maxResults) {
                break;
            }
            if (rawItem instanceof Map<?, ?> item) {
                results.add(new SearchResultItem(
                        stringOrEmpty(item.get("title")),
                        stringOrEmpty(item.get("link")),
                        stringOrEmpty(item.get("snippet"))));
            }
        }
        return results;
    }

    private static String stringOrEmpty(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
