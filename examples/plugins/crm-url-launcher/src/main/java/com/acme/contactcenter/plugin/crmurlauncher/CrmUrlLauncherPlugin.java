package com.acme.contactcenter.plugin.crmurlauncher;

import com.contactcenter.pluginsdk.PluginContext;
import com.contactcenter.pluginsdk.PluginEntryPoint;
import com.contactcenter.pluginsdk.model.ContactEvent;
import com.contactcenter.pluginsdk.model.CustomerView;
import com.contactcenter.pluginsdk.model.ManualActionRequest;
import com.contactcenter.pluginsdk.model.ManualActionResult;
import com.contactcenter.pluginsdk.model.PreContactConnectResult;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Punkt wejściowy pluginu "CRM URL Launcher" (documentation/10-plugin-development.md).
 *
 * <p>Cel: podczas rozmowy z klientem panel boczny agenta umożliwia jednym kliknięciem
 * otwarcie profilu klienta w zewnętrznym systemie CRM. URL CRM jest budowany z szablonu
 * konfigurowanego per-tenant ({@code crmUrlTemplate}), z automatycznym podstawieniem danych
 * klienta i opcjonalnych parametrów wpisywanych przez agenta ({@code agentParams}).
 *
 * <p>Dwa punkty rozszerzeń:
 * <ul>
 *   <li>{@code PRE_CONTACT_CONNECT} — przy połączeniu agenta z kontaktem zwraca dane klienta
 *       i listę wymaganych parametrów agenta jako {@code displayData}, pozwalając hostowi
 *       wyświetlić je w panelu klienta przed załadowaniem samego panelu bocznego;</li>
 *   <li>{@code MANUAL_ACTION} — obsługuje dwie akcje wywołane z panelu bocznego:
 *       <ul>
 *         <li>{@code get-crm-context} — zwraca dane klienta i podgląd szablonu URL;</li>
 *         <li>{@code build-crm-url} — buduje finalny URL z parametrów agenta i danych
 *             klienta, gotowy do otwarcia przez {@code PluginUiSdk.openUrl()}.</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p>Zero zewnętrznych zależności — tylko plugin-sdk i JDK. Brak wywołań HTTP: URL CRM jest
 * budowany lokalnie z danych dostępnych przez {@link com.contactcenter.pluginsdk.PluginContext}.
 */
public class CrmUrlLauncherPlugin implements PluginEntryPoint {

    private static final String ACTION_GET_CRM_CONTEXT = "get-crm-context";
    private static final String ACTION_BUILD_CRM_URL = "build-crm-url";

    @Override
    public void onActivate(PluginContext context) {
        // Rzucenie tutaj przerywa aktywację — instalacja zostaje "enabled=false" z błędem
        // widocznym dla administratora tenanta (kontrakt PluginEntryPoint#onActivate).
        context.config().get("crmUrlTemplate")
                .orElseThrow(() -> new IllegalStateException(
                        "Plugin wymaga konfiguracji 'crmUrlTemplate' (szablon URL CRM "
                                + "z placeholderami np. {customerId}) ustawionej przez "
                                + "administratora tenanta."));

        List<String> agentParams = parseAgentParams(
                context.config().getOrDefault("agentParams", ""));
        context.logger().info(
                "CRM URL Launcher aktywowany. Parametry agenta: " + agentParams);
    }

    @Override
    public void onDeactivate() {
        // Brak zasobów do zwolnienia — plugin nie utrzymuje żadnych połączeń ani cache.
    }

    /**
     * Zwraca dane klienta jako {@code displayData} przy połączeniu agenta z kontaktem.
     *
     * <p>Jeśli {@code customerId} jest null (np. nieznany dzwoniący), zwraca pusty wynik
     * — host i tak połączy rozmowę bez danych pluginu.
     */
    @Override
    public PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) {
        if (e.customerId() == null) {
            return PreContactConnectResult.empty();
        }
        try {
            CustomerView customer = ctx.getCustomer(e.customerId());
            List<String> agentParams = parseAgentParams(
                    ctx.config().getOrDefault("agentParams", ""));

            Map<String, Object> displayData = new LinkedHashMap<>();
            displayData.put("customerName", fullName(customer));
            displayData.put("customerPhone", firstOrEmpty(customer.phoneNumbers()));
            displayData.put("agentParams", agentParams);
            return new PreContactConnectResult(displayData, null);
        } catch (RuntimeException ex) {
            ctx.logger().warn(
                    "Nie udało się pobrać danych klienta przy PRE_CONTACT_CONNECT: "
                            + ex.getMessage());
            return PreContactConnectResult.empty();
        }
    }

    /**
     * Obsługuje akcje manualne wywołane z panelu bocznego agenta.
     *
     * <p>Każdy wyjątek jest zawierany i zwracany jako {@code success=false} —
     * nigdy nie propagowany do hosta, żeby błąd pluginu nie zakłócił pracy agenta.
     */
    @Override
    public ManualActionResult onManualAction(PluginContext ctx, ManualActionRequest req) {
        try {
            if (ACTION_GET_CRM_CONTEXT.equals(req.actionId())) {
                return handleGetCrmContext(ctx, req);
            } else if (ACTION_BUILD_CRM_URL.equals(req.actionId())) {
                return handleBuildCrmUrl(ctx, req);
            } else {
                return ManualActionResult.unsupported();
            }
        } catch (RuntimeException ex) {
            ctx.logger().error(
                    "Błąd akcji '" + req.actionId() + "': " + ex.getMessage(), ex);
            return new ManualActionResult(false, Map.of(),
                    "Wewnętrzny błąd pluginu: " + ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Prywatne metody obsługi akcji
    // -------------------------------------------------------------------------

    /**
     * Akcja {@code get-crm-context}: zwraca dane klienta i podgląd szablonu URL potrzebne
     * do wyrenderowania formularza w panelu bocznym agenta.
     *
     * <p>Wynik zawiera:
     * <ul>
     *   <li>{@code customerName} — imię i nazwisko klienta (lub pusty string);</li>
     *   <li>{@code customerPhone} — pierwszy numer telefonu (lub pusty string);</li>
     *   <li>{@code customerEmail} — pierwszy adres e-mail (lub pusty string);</li>
     *   <li>{@code agentParams} — lista nazw parametrów do wypełnienia przez agenta;</li>
     *   <li>{@code templatePreview} — szablon URL z podstawionymi zmiennymi automatycznymi
     *       i agentParams wyświetlanymi jako {@code <nazwaParametru>}.</li>
     * </ul>
     */
    private ManualActionResult handleGetCrmContext(PluginContext ctx, ManualActionRequest req) {
        String template = ctx.config().getOrDefault("crmUrlTemplate", "");
        List<String> agentParams = parseAgentParams(
                ctx.config().getOrDefault("agentParams", ""));

        Map<String, Object> resultData = new LinkedHashMap<>();

        if (req.customerId() != null) {
            CustomerView customer = ctx.getCustomer(req.customerId());
            resultData.put("customerName", fullName(customer));
            resultData.put("customerPhone", firstOrEmpty(customer.phoneNumbers()));
            resultData.put("customerEmail", firstOrEmpty(customer.emails()));

            // Podgląd szablonu: podstaw zmienne auto, zostaw pola agenta jako <paramName>
            Map<String, String> autoVars = buildAutoVariables(
                    customer, req.customerId(), req.contactId());
            resultData.put("templatePreview",
                    buildTemplatePreview(template, autoVars, agentParams));
        } else {
            resultData.put("customerName", "");
            resultData.put("customerPhone", "");
            resultData.put("customerEmail", "");
            resultData.put("templatePreview", template);
        }
        resultData.put("agentParams", agentParams);

        return new ManualActionResult(true, resultData, null);
    }

    /**
     * Akcja {@code build-crm-url}: buduje finalny URL CRM przez podstawienie WSZYSTKICH
     * zmiennych (automatycznych z danych klienta i parametrów agenta z {@code req.parameters}).
     *
     * <p>Jeśli po podstawieniu w URL pozostają nierozwiązane placeholdery {@code {klucz}},
     * zwraca {@code success=false} z czytelnym komunikatem — pozwala panelowi pokazać błąd
     * agentowi zamiast otwierać błędny URL.
     */
    private ManualActionResult handleBuildCrmUrl(PluginContext ctx, ManualActionRequest req) {
        String template = ctx.config().getOrDefault("crmUrlTemplate", "");
        if (template.isBlank()) {
            return new ManualActionResult(false, Map.of(),
                    "Brak konfiguracji 'crmUrlTemplate'. Skontaktuj się z administratorem.");
        }

        Map<String, String> allVars = new HashMap<>();

        // Zmienne automatyczne z danych klienta
        if (req.customerId() != null) {
            CustomerView customer = ctx.getCustomer(req.customerId());
            allVars.putAll(buildAutoVariables(customer, req.customerId(), req.contactId()));
        } else if (req.contactId() != null) {
            // Brak klienta, ale contactId jest dostępne — podstaw przynajmniej to
            allVars.put("contactId", req.contactId().toString());
        }

        // Parametry agenta z req.parameters (mają priorytet — mogą nadpisać auto-zmienne)
        for (Map.Entry<String, Object> entry : req.parameters().entrySet()) {
            if (entry.getValue() != null) {
                allVars.put(entry.getKey(), entry.getValue().toString());
            }
        }

        String url = UrlTemplateBuilder.build(template, allVars);
        List<String> unresolved = UrlTemplateBuilder.findUnresolved(url);
        if (!unresolved.isEmpty()) {
            return new ManualActionResult(false, Map.of(),
                    "Nie można zbudować URL — brakujące parametry: " + unresolved
                            + ". Wypełnij wszystkie wymagane pola.");
        }

        ctx.logger().info("Zbudowano URL CRM dla klienta " + req.customerId());
        return new ManualActionResult(true, Map.of("url", url), null);
    }

    // -------------------------------------------------------------------------
    // Metody pomocnicze
    // -------------------------------------------------------------------------

    /**
     * Buduje mapę zmiennych automatycznych z danych klienta i identyfikatorów kontaktu.
     * Wszystkie wartości null są zastępowane pustym stringiem — szablon nie powinien
     * pozostawić żadnego placeholder nierozwiązanego z powodu null.
     */
    private static Map<String, String> buildAutoVariables(
            CustomerView customer, UUID customerId, UUID contactId) {
        Map<String, String> vars = new HashMap<>();
        vars.put("customerId", customerId != null ? customerId.toString() : "");
        vars.put("contactId", contactId != null ? contactId.toString() : "");
        vars.put("customerPhone", firstOrEmpty(customer.phoneNumbers()));
        vars.put("customerEmail", firstOrEmpty(customer.emails()));
        vars.put("customerFirstName",
                customer.firstName() != null ? customer.firstName() : "");
        vars.put("customerLastName",
                customer.lastName() != null ? customer.lastName() : "");
        vars.put("customerExternalId",
                customer.externalId() != null ? customer.externalId() : "");
        return vars;
    }

    /**
     * Buduje podgląd szablonu URL: zmienne automatyczne są podstawiane (URL-encoded),
     * a pola agenta pozostają jako {@code <nazwaParametru>} (czytelna dla człowieka
     * informacja o tym, co agent musi uzupełnić).
     */
    private static String buildTemplatePreview(
            String template, Map<String, String> autoVars, List<String> agentParams) {
        // Krok 1: podstaw zmienne automatyczne (URL-encoded) — pola agenta pozostają jako {param}
        String preview = UrlTemplateBuilder.build(template, autoVars);
        // Krok 2: zamień pozostałe {param} na <param> dla czytelności
        for (String param : agentParams) {
            preview = preview.replace("{" + param + "}", "<" + param + ">");
        }
        return preview;
    }

    /**
     * Parsuje konfigurację {@code agentParams} (przecinkami oddzielona lista nazw parametrów).
     * Pusta konfiguracja lub brak klucza daje pustą listę — plugin działa bez pól agenta.
     */
    private static List<String> parseAgentParams(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Zwraca "Imię Nazwisko" (trim, bez podwójnych spacji), lub pusty string gdy oba null. */
    private static String fullName(CustomerView customer) {
        String first = customer.firstName() != null ? customer.firstName().trim() : "";
        String last = customer.lastName() != null ? customer.lastName().trim() : "";
        return (first + " " + last).trim();
    }

    /** Pierwszy element listy, lub pusty string gdy lista null/pusta. */
    private static String firstOrEmpty(List<String> list) {
        return (list == null || list.isEmpty()) ? "" : list.get(0);
    }
}
