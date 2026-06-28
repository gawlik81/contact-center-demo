package com.acme.contactcenter.plugin.crmurlauncher;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pomocnicza klasa do podstawiania zmiennych w szablonach URL (np.
 * {@code https://crm.example.com/customer?id={customerId}&phone={customerPhone}}).
 *
 * <p>Placeholdery mają format {@code {nazwaZmiennej}} — jednoznakowe nawiasy klamrowe.
 * Zmienne znalezione w mapie są URL-encodeowane (RFC 3986, spacja → {@code %20}).
 * Zmienne NIE znalezione w mapie pozostają w szablonie bez zmian, co pozwala wykryć
 * je po fakcie metodą {@link #findUnresolved(String)}.
 *
 * <p>Klasa jest package-private — używana wyłącznie przez {@link CrmUrlLauncherPlugin};
 * nie jest częścią publicznego API pluginu.
 */
class UrlTemplateBuilder {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    private UrlTemplateBuilder() {
    }

    /**
     * Podstawia zmienne z mapy do szablonu URL.
     *
     * <p>Każde wystąpienie {@code {klucz}} jest zastępowane URL-enkodowaną wartością z mapy
     * (spacje kodowane jako {@code %20}, zgodnie z RFC 3986). Placeholdery, których kluczy
     * nie ma w mapie, pozostają w wynikowym stringu bez zmian ({@code {klucz}}).
     *
     * @param template  szablon URL z placeholderami w formacie {@code {klucz}}
     * @param variables mapa zmiennych do podstawienia; wartości mogą zawierać dowolne znaki —
     *                  będą URL-enkodowane
     * @return URL po podstawieniu dostępnych zmiennych
     */
    static String build(String template, Map<String, String> variables) {
        StringBuffer sb = new StringBuffer();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (variables.containsKey(key)) {
                String encoded = URLEncoder.encode(variables.get(key), StandardCharsets.UTF_8)
                        .replace("+", "%20");
                matcher.appendReplacement(sb, Matcher.quoteReplacement(encoded));
            } else {
                // Placeholder nierozwiązany — zachowaj oryginał {klucz}
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(0)));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Zwraca listę nazw placeholderów, które pozostały nierozwiązane w podanym stringu.
     *
     * <p>Wywoływany po {@link #build(String, Map)} na wynikowym URL — jeśli lista jest
     * niepusta, URL zawiera jeszcze niezastąpione zmienne i nie powinien być używany.
     *
     * @param url string (URL lub fragment szablonu) do przeszukania
     * @return lista nazw nierozwiązanych zmiennych (bez nawiasów), w kolejności wystąpienia;
     *         pusta gdy URL jest kompletny
     */
    static List<String> findUnresolved(String url) {
        List<String> unresolved = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(url);
        while (matcher.find()) {
            unresolved.add(matcher.group(1));
        }
        return unresolved;
    }
}
