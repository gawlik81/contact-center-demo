package com.acme.contactcenter.plugin.googlelookup;

import com.contactcenter.pluginsdk.PluginContext;
import com.contactcenter.pluginsdk.PluginEntryPoint;
import com.contactcenter.pluginsdk.model.ContactEvent;
import com.contactcenter.pluginsdk.model.CustomerView;
import com.contactcenter.pluginsdk.model.ManualActionRequest;
import com.contactcenter.pluginsdk.model.ManualActionResult;
import com.contactcenter.pluginsdk.model.PreContactConnectResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Punkt wejściowy pluginu "Customer Google Lookup" (documentation/10-plugin-development.md).
 *
 * <p>Cel: panel boczny agenta aktywowany podczas rozmowy, prezentujący wyniki wyszukiwania
 * Google dla danych bieżącego klienta (imię + nazwisko, z awaryjnym numerem telefonu, gdy
 * klient nie ma zapisanego imienia/nazwiska).
 *
 * <p>Dwa punkty rozszerzeń:
 * <ul>
 *   <li>{@code PRE_CONTACT_CONNECT} — automatyczne wyszukiwanie w momencie połączenia agenta
 *       z kontaktem (wynik trafia do panelu klienta agenta jako {@code displayData});</li>
 *   <li>{@code MANUAL_ACTION} ({@code refresh-google-search}) — odświeżenie na żądanie, wołane
 *       przez panel boczny pluginu (zob. {@code plugin-ui/index.html}) przy każdym montowaniu,
 *       oraz przez przycisk w toolbarze agenta (manifest {@code manualActions}).</li>
 * </ul>
 *
 * <p>Jedyny kanał sieciowy to {@link PluginContext#httpClient()} — host wymusza allow-listę
 * hostów z manifestu PRZED każdym wywołaniem; ten kod nigdy nie próbuje (i nie mógłby) wywołać
 * żadnego innego hosta niż {@code www.googleapis.com}.
 */
public class CustomerGoogleLookupPlugin implements PluginEntryPoint {

    private static final String ACTION_REFRESH = "refresh-google-search";
    private static final int MAX_RESULTS = 5;

    @Override
    public void onActivate(PluginContext context) {
        // Rzucenie tutaj przerywa aktywację — instalacja zostaje "enabled=false" z błędem
        // widocznym dla administratora tenanta (kontrakt PluginEntryPoint#onActivate).
        // Demonstruje wymaganą konfigurację per tenant przed użyciem pluginu.
        context.config().get("googleApiKey")
                .orElseThrow(() -> new IllegalStateException(
                        "Plugin wymaga konfiguracji 'googleApiKey' (klucz Google Custom Search "
                                + "API) ustawionej przez administratora tenanta."));
        context.config().get("googleSearchEngineId")
                .orElseThrow(() -> new IllegalStateException(
                        "Plugin wymaga konfiguracji 'googleSearchEngineId' (identyfikator "
                                + "Custom Search Engine, parametr 'cx')."));
        context.logger().info("Customer Google Lookup aktywowany.");
    }

    @Override
    public void onDeactivate() {
        // Brak zasobów do zwolnienia – HttpEgressClient jest bezstanowy per wywołanie.
    }

    @Override
    public PreContactConnectResult onPreContactConnect(PluginContext ctx, ContactEvent e) {
        if (e.customerId() == null) {
            return PreContactConnectResult.empty();
        }
        return runSearch(ctx, e.customerId())
                .map(result -> new PreContactConnectResult(result, null))
                .orElseGet(PreContactConnectResult::empty);
    }

    @Override
    public ManualActionResult onManualAction(PluginContext ctx, ManualActionRequest req) {
        if (!ACTION_REFRESH.equals(req.actionId())) {
            return ManualActionResult.unsupported();
        }
        if (req.customerId() == null) {
            return new ManualActionResult(false, Map.of(),
                    "Brak klienta powiązanego z bieżącym kontaktem.");
        }
        return runSearch(ctx, req.customerId())
                .map(result -> new ManualActionResult(true, result, null))
                .orElseGet(() -> new ManualActionResult(false, Map.of(),
                        "Brak wyników wyszukiwania Google dla tego klienta."));
    }

    /**
     * Buduje zapytanie z danych klienta i woła Google Custom Search API. Każdy błąd (sieć,
     * brak konfiguracji, brak wyników) jest zawierany TUTAJ i zwracany jako "brak wyniku" —
     * nigdy propagowany dalej. Wyszukiwarka Google nie może zablokować, spowolnić ani zepsuć
     * połączenia agenta z klientem; w najgorszym przypadku agent po prostu nie zobaczy danych
     * z Google (host i tak otacza całe wywołanie pluginu własnym timeoutem/catch(Throwable),
     * ale ta metoda jest dodatkową, jawną warstwą — błąd HTTP nie powinien nawet dotrzeć do
     * tej granicy jako wyjątek nieobsłużony).
     */
    private Optional<Map<String, Object>> runSearch(PluginContext ctx, UUID customerId) {
        try {
            CustomerView customer = ctx.getCustomer(customerId);
            String query = buildQuery(customer);
            if (query.isBlank()) {
                return Optional.empty();
            }
            List<SearchResultItem> items = GoogleCustomSearchClient.search(ctx, query, MAX_RESULTS);
            if (items.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("query", query);
            result.put("results", items.stream().map(SearchResultItem::toMap).toList());
            return Optional.of(result);
        } catch (RuntimeException ex) {
            ctx.logger().warn("Wyszukiwanie Google nie powiodło się: " + ex.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Imię + nazwisko klienta, z awaryjnym pierwszym numerem telefonu gdy żadne z nich nie
     * jest zapisane (np. nowy/anonimowy kontakt rozpoznany tylko po CLI).
     */
    private static String buildQuery(CustomerView customer) {
        String firstName = customer.firstName() == null ? "" : customer.firstName().trim();
        String lastName = customer.lastName() == null ? "" : customer.lastName().trim();
        String name = (firstName + " " + lastName).trim();
        if (!name.isBlank()) {
            return name;
        }
        if (!customer.phoneNumbers().isEmpty()) {
            return customer.phoneNumbers().get(0);
        }
        return "";
    }
}
