package com.acme.contactcenter.plugin.callresultdbsync;

import com.contactcenter.pluginsdk.PluginContext;
import com.contactcenter.pluginsdk.PluginEntryPoint;
import com.contactcenter.pluginsdk.model.ContactEvent;
import com.contactcenter.pluginsdk.model.ContactView;
import com.contactcenter.pluginsdk.model.DispositionEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Punkt wejściowy pluginu "Customer Call Result DB Sync" (documentation/10-plugin-development.md).
 *
 * <p>Cel: append-only log zakończonych kontaktów i ustawionych dyspozycji, zapisywany do
 * zewnętrznej bazy danych tenanta (dowolny silnik JDBC dostępny na classpath hosta — patrz
 * README.md, sekcja "Known limitation"). Jeden wiersz na zdarzenie — NIE upsert. Dla
 * {@code CONTACT_ENDED} stosujemy {@code INSERT … WHERE NOT EXISTS} jako idempotentny
 * guard przed duplikatami wynikającymi z gwarancji at-least-once delivery RabbitMQ lub
 * podwójnej emisji eventu przez platformę przy pewnych scenariuszach rozłączenia.
 *
 * <p>Dwa punkty rozszerzeń, oba fire-and-forget (dispatched asynchronicznie przez
 * {@code cc.queue.plugin-invocation} — plugin latency/failure nigdy nie wpływa na pulpit
 * agenta):
 * <ul>
 *   <li>{@code POST_CONTACT_END} — kontakt się zakończył, dociągamy pełny snapshot przez
 *       {@code ctx.getContact(e.contactId())} (event niesie tylko {@code contactId});</li>
 *   <li>{@code DISPOSITION_SET} — agent ustawił dyspozycję (event niesie już wszystkie
 *       potrzebne pola bezpośrednio, bez potrzeby dociągania).</li>
 * </ul>
 *
 * <p>Jedyny kanał dostępu do bazy zewnętrznej to {@link PluginContext#dbClient()} — host
 * wymusza allow-listę {@code db:egress:<host>:<port>} z manifestu PRZED każdym wywołaniem; ten
 * kod nigdy nie widzi (i nie mógłby zobaczyć) JDBC URL/credentiali — host zarządza połączeniem
 * w całości na podstawie {@code installation_config} tej instalacji.
 */
public class CustomerCallResultDbSyncPlugin implements PluginEntryPoint {

    private static final String DEFAULT_TABLE = "call_results";

    /** Strefa czasowa używana do zapisu occurred_at — czas lokalny Polski (UTC+1/UTC+2 z DST). */
    private static final ZoneId ZONE_WARSAW = ZoneId.of("Europe/Warsaw");

    /**
     * Walidacja nazwy tabeli — interpolowana w SQL identifier position (nie da się
     * parametryzować nazwy tabeli przez {@code ?}), więc musi być sprawdzona przed budową SQL.
     * To jest config ustawiany przez admina tenanta instalującego plugin, nie input użytkownika
     * końcowego — ale warto się defensywnie zabezpieczyć, żeby admin (nawet przez przypadek) nie
     * mógł wstrzyknąć czegoś poza prostą nazwą tabeli.
     */
    private static final Pattern TABLE_NAME_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    @Override
    public void onActivate(PluginContext context) {
        // Rzucenie tutaj przerywa aktywację — instalacja zostaje "enabled=false" z błędem
        // widocznym dla administratora tenanta (kontrakt PluginEntryPoint#onActivate).
        // Walidujemy konfigurację wymaganą do połączenia z bazą zewnętrznej, ale NIE próbujemy
        // się faktycznie połączyć tutaj (DbEgressClient łączy się tylko per executeUpdate,
        // fire-and-forget — onActivate jest wołane synchronicznie z flow administratora i nie
        // powinno zależeć od dostępności zewnętrznej bazy w tym momencie).
        context.config().get("jdbcUrl")
                .orElseThrow(() -> new IllegalStateException(
                        "Plugin wymaga konfiguracji 'jdbcUrl' (JDBC URL zewnętrznej bazy danych) "
                                + "ustawionej przez administratora tenanta."));
        String table = context.config().getOrDefault("dbTable", DEFAULT_TABLE);
        if (!isValidTableName(table)) {
            throw new IllegalStateException(
                    "Konfiguracja 'dbTable' musi być prostą nazwą tabeli ([a-zA-Z_][a-zA-Z0-9_]*), otrzymano: '"
                            + table + "'");
        }
        context.logger().info("Customer Call Result DB Sync aktywowany, tabela docelowa: " + table);
    }

    @Override
    public void onDeactivate() {
        // Brak zasobów do zwolnienia — DbEgressClient jest bezstanowy per wywołanie (brak
        // poolingu, zob. Javadoc PluginDbEgressClientImpl po stronie hosta).
    }

    @Override
    public void onPostContactEnd(PluginContext ctx, ContactEvent e) {
        try {
            ContactView contact = ctx.getContact(e.contactId());
            String table = resolveTableName(ctx);
            // INSERT ... WHERE NOT EXISTS zamiast zwykłego INSERT VALUES:
            // RabbitMQ gwarantuje at-least-once delivery, a platforma może emitować
            // POST_CONTACT_END dwukrotnie przy niektórych scenariuszach rozłączenia
            // (np. równoczesny webhook statusu połączenia i hangup po stronie agenta).
            // WHERE NOT EXISTS jest przenośne między PostgreSQL, MySQL i SQL Server.
            // Oracle wymaga dodatku "FROM DUAL" po SELECT.
            String sql = "INSERT INTO " + table
                    + " (contact_id, customer_id, event_type, channel, direction, status,"
                    + "  agent_id, disposition_code, occurred_at)"
                    + " SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?"
                    + " WHERE NOT EXISTS ("
                    + "   SELECT 1 FROM " + table
                    + "   WHERE contact_id = ? AND event_type = 'CONTACT_ENDED'"
                    + ")";
            List<Object> params = new ArrayList<>();
            params.add(contact.contactId());
            params.add(contact.customerId());
            params.add("CONTACT_ENDED");
            params.add(contact.channel());
            params.add(contact.direction());
            params.add(contact.status());
            params.add(contact.agentId());
            params.add(null); // disposition_code — nieznany w tym evencie, kolumna nullable
            params.add(toTimestampParam(contact.endedAt() != null ? contact.endedAt() : e.occurredAt()));
            params.add(contact.contactId()); // parametr WHERE NOT EXISTS

            ctx.dbClient().executeUpdate(sql, params);
        } catch (RuntimeException ex) {
            ctx.logger().warn("Zapis wyniku zakończonego kontaktu do DB nie powiódł się: " + ex.getMessage());
        }
    }

    @Override
    public void onDispositionSet(PluginContext ctx, DispositionEvent e) {
        try {
            String table = resolveTableName(ctx);
            String sql = "INSERT INTO " + table
                    + " (contact_id, customer_id, event_type, channel, direction, status, "
                    + "agent_id, disposition_code, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            List<Object> params = new ArrayList<>();
            params.add(e.contactId());
            params.add(e.customerId());
            params.add("DISPOSITION_SET");
            params.add(null); // channel/direction/status nieznane z tego eventu — DispositionEvent
            params.add(null); // niesie tylko contactId/customerId/dispositionCode/agentId/setAt
            params.add(null);
            params.add(e.agentId());
            params.add(e.dispositionCode());
            params.add(toTimestampParam(e.setAt()));

            ctx.dbClient().executeUpdate(sql, params);
        } catch (RuntimeException ex) {
            ctx.logger().warn("Zapis dyspozycji do DB nie powiódł się: " + ex.getMessage());
        }
    }

    private static String resolveTableName(PluginContext ctx) {
        String table = ctx.config().getOrDefault("dbTable", DEFAULT_TABLE);
        if (!isValidTableName(table)) {
            // onActivate już to sprawdził przy aktywacji, ale config może się zmienić bez
            // ponownej aktywacji (PATCH .../config nie wymaga re-enable) — druga, defensywna
            // walidacja bezpośrednio przed budową SQL.
            throw new IllegalStateException(
                    "Konfiguracja 'dbTable' musi być prostą nazwą tabeli ([a-zA-Z_][a-zA-Z0-9_]*), otrzymano: '"
                            + table + "'");
        }
        return table;
    }

    private static boolean isValidTableName(String table) {
        return table != null && TABLE_NAME_PATTERN.matcher(table).matches();
    }

    private static java.sql.Timestamp toTimestampParam(Instant instant) {
        if (instant == null) return null;
        // Konwertuj UTC → czas lokalny Warszawy przed zapisem do TIMESTAMP (bez strefy).
        // Timestamp.from(instant) zapisuje czas w UTC (JVM epoch millis), co przy
        // TIMESTAMP WITHOUT TIME ZONE skutkuje wartościami o 1–2h wcześniejszymi niż
        // oczekuje użytkownik. valueOf(LocalDateTime) zapisuje czas lokalny dosłownie.
        ZonedDateTime local = instant.atZone(ZONE_WARSAW);
        return java.sql.Timestamp.valueOf(local.toLocalDateTime());
    }
}
