package com.contactcenter.infrastructure.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Walidacja polityk RLS (Row-Level Security) przy starcie aplikacji (BE-138, EPIC-30).
 *
 * <p><strong>Zakres (lista tabel pochodzi z zapytania SQL, NIE z twardej listy w kodzie):</strong>
 * przed BE-138 ta klasa sprawdzała 11 zahardkodowanych nazw tabel i tylko to, czy mają JAKĄKOLWIEK
 * politykę w {@code pg_policies} — polityka tylko-SELECT przechodziła tę walidację, mimo że zapisy pod
 * rolą ograniczoną (bez BYPASSRLS) były wtedy po cichu odrzucane (DESIGN §2 U8). Metodologia poniższego
 * zapytania odtwarza klasyfikację z raportu DB-071 ({@code information_schema.columns} ⋈ {@code pg_class},
 * partycje potomne wykluczone przez {@code pg_inherits}): klasa TENANT = tabela z kolumną
 * {@code tenant_id NOT NULL}, z wyjątkiem dwóch katalogów klasy GLOBAL:
 * <ul>
 *     <li>{@code tenant} — {@code tenant_id} tu jest PK encji najemcy, nie kolumną izolacji;</li>
 *     <li>{@code plugin_version} — {@code tenant_id} to właściciel wgranej wersji (ownership, DB-042),
 *     nie izolacja widoczności globalnego katalogu pluginów.</li>
 * </ul>
 * Tabele klasy MIXED z DB-071 ({@code tenant_id} NULLABLE, NULL = rekord globalny — {@code audit_log},
 * {@code app_user}, {@code refresh_token}, {@code gdpr_processing_register}) są ŚWIADOMIE wyłączone z tej
 * walidacji (naturalnie, przez warunek {@code is_nullable = 'NO'} w zapytaniu) — mają inną, celowo
 * luźniejszą semantykę polityk ({@code tenant_id IS NULL OR ...}) i osobne decyzje właściciela (DB-071
 * klasa C), więc nie są „klasą TENANT" w rozumieniu tego raportu.
 *
 * <p><strong>Co jest sprawdzane dla każdej tabeli klasy TENANT:</strong>
 * <ol>
 *     <li>Pokrycie komend: polityka {@code cmd = 'ALL'} ALBO komplet czterech osobnych polityk
 *     ({@code SELECT}, {@code INSERT}, {@code UPDATE}, {@code DELETE}) w {@code pg_policies}. Polityka
 *     tylko-SELECT (albo jakikolwiek inny niepełny podzbiór) jest naruszeniem — zapisy pod rolą
 *     ograniczoną są wtedy po cichu odrzucane (0 wierszy / {@code ERROR 42501}), nie zablokowane
 *     widocznie.</li>
 *     <li>{@code relrowsecurity} w {@code pg_class} — bez {@code ENABLE ROW LEVEL SECURITY} polityki
 *     istnieją w katalogu, ale nie są wykonywane wcale.</li>
 *     <li>{@code relforcerowsecurity} w {@code pg_class} — bez {@code FORCE ROW LEVEL SECURITY}
 *     właściciel tabeli (rola, która tworzyła tabelę migracją) omija RLS niezależnie od polityk.</li>
 * </ol>
 *
 * <p><strong>Rola połączenia:</strong> {@code SELECT rolsuper, rolbypassrls FROM pg_roles WHERE
 * rolname = current_user} — gdy połączenie aplikacji używa roli superuser/BYPASSRLS (tak jak
 * {@code ccapp} w local-demo), CAŁA powyższa walidacja jest wyłącznie defense-in-depth: taka rola
 * ignoruje RLS niezależnie od pokrycia komend i FORCE, a jedyną realną ochroną przed wyciekiem danych
 * między tenantami jest {@code assertSameTenant}/{@code TenantAwareRepository} na poziomie aplikacji.
 *
 * <p><strong>WAŻNE — nie blokuje startu, z JEDNYM świadomym wyjątkiem:</strong> naruszenia pokrycia
 * komend / FORCE logują tylko WARNING (środowiska testowe/dev mogą mieć niekompletny schemat — np.
 * Testcontainers z pełnym Flyway, ale ticket DB-074 jeszcze nie wdrożony). Jedyny wyjątek: flaga
 * {@code rls.validation.fail-on-bypass} (domyślnie {@code false}) — gdy {@code true} I rola połączenia
 * omija RLS (superuser/BYPASSRLS), metoda rzuca {@link IllegalStateException} i PRZERYWA start.
 * Decyzja świadoma, udokumentowana tutaj: ta flaga ma sens WYŁĄCZNIE jako przełącznik produkcyjny
 * („nigdy nie wdróż tej roli na prod") — samo logowanie WARN zamiast ERROR nie dawałoby żadnej nowej
 * gwarancji (WARN już się dzieje domyślnie), więc jedyny sposób, żeby flaga cokolwiek faktycznie
 * zmieniała, to realne zatrzymanie startu. Pozostałe naruszenia (pokrycie komend, FORCE) NIE rzucają
 * nawet przy {@code fail-on-bypass=true} — flaga dotyczy wyłącznie roli połączenia, zgodnie z treścią
 * AC BE-138.
 *
 * <p>Uwaga BE-134 / DB-067: {@code email_message} jest partycjonowana RANGE po {@code message_at}
 * (podobnie {@code social_message}, {@code contact}, {@code contact_event}, {@code campaign_contact}
 * i inne). Polityki RLS siedzą na tabeli nadrzędnej — partycje potomne są wykluczone z zapytania przez
 * {@code pg_inherits} (nie mają własnych polityk, dziedziczą z rodzica).
 *
 * <p>Konsekwencja zapytania SQL zamiast twardej listy: zbiór naruszeń zmienia się w czasie wraz ze
 * schematem (np. po wdrożeniu DB-074 naruszenia dla tabel klasy B z DB-071 — {@code contact},
 * {@code campaign}, {@code customer}, {@code queue}, {@code ivr_tree} — powinny zniknąć) — to jest
 * ZAMIERZONE, nie regresja. Ten komponent ma działać poprawnie w OBU stanach, przed i po DB-074.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RlsValidationService {

    /** Klasa GLOBAL z DB-071 — {@code tenant_id NOT NULL}, ale świadomie bez izolacji RLS. */
    private static final Set<String> GLOBAL_CLASS_TABLES = Set.of("tenant", "plugin_version");

    private static final Set<String> REQUIRED_COMMANDS = Set.of("SELECT", "INSERT", "UPDATE", "DELETE");

    private final JdbcTemplate jdbcTemplate;

    /**
     * Przełącznik produkcyjny (domyślnie wyłączony) — gdy {@code true} i rola połączenia omija RLS
     * (superuser/BYPASSRLS), {@link #validateRlsPolicies()} rzuca {@link IllegalStateException} i
     * przerywa start aplikacji. Zob. Javadoc klasy, sekcja „nie blokuje startu, z JEDNYM wyjątkiem".
     */
    @Value("${rls.validation.fail-on-bypass:false}")
    private boolean failOnBypass;

    @EventListener(ApplicationReadyEvent.class)
    public void validateRlsPolicies() {
        List<RlsViolation> violations;
        RoleBypassInfo roleInfo;
        try {
            List<String> tenantTables = findTenantClassTables();
            violations = findCommandCoverageViolations(tenantTables);
            roleInfo = checkConnectionRole();
        } catch (Exception e) {
            // Nie blokuj startu aplikacji przy błędzie samej walidacji (np. brak uprawnień do
            // pg_catalog/information_schema, środowisko bez tych katalogów systemowych).
            log.warn("[RlsValidation] Nie można zweryfikować polityk RLS: {}", e.getMessage());
            return;
        }

        logViolations(violations);
        logOrEnforceRoleInfo(roleInfo);
    }

    // =========================================================================
    // 1. Lista tabel klasy TENANT (DB-071) — zapytanie SQL, nie twarda lista
    // =========================================================================

    /**
     * Zwraca nazwy tabel klasy TENANT z klasyfikacji DB-071: tabele z kolumną {@code tenant_id NOT
     * NULL}, bez partycji potomnych ({@code pg_inherits}) i bez katalogów klasy GLOBAL ({@code tenant},
     * {@code plugin_version}). Tabele klasy MIXED ({@code tenant_id} nullable) są wykluczone naturalnie
     * przez warunek {@code is_nullable = 'NO'}.
     */
    List<String> findTenantClassTables() {
        return jdbcTemplate.queryForList(
                """
                SELECT c.relname
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN information_schema.columns col
                     ON col.table_schema = n.nspname
                    AND col.table_name = c.relname
                    AND col.column_name = 'tenant_id'
                WHERE n.nspname = 'public'
                  AND c.relkind IN ('r', 'p')
                  AND col.is_nullable = 'NO'
                  AND NOT EXISTS (SELECT 1 FROM pg_inherits i WHERE i.inhrelid = c.oid)
                  AND c.relname NOT IN ('tenant', 'plugin_version')
                ORDER BY c.relname
                """,
                String.class
        );
    }

    // =========================================================================
    // 2. Pokrycie komend + FORCE
    // =========================================================================

    /**
     * Dla każdej podanej tabeli sprawdza pokrycie komend RLS ({@code cmd='ALL'} albo komplet
     * SELECT/INSERT/UPDATE/DELETE), {@code relrowsecurity} i {@code relforcerowsecurity}. Zwraca
     * wyłącznie tabele z co najmniej jednym naruszeniem (puste {@link List} = brak naruszeń).
     */
    List<RlsViolation> findCommandCoverageViolations(List<String> tables) {
        if (tables.isEmpty()) {
            return List.of();
        }

        Map<String, Set<String>> commandsByTable = fetchPolicyCommandsByTable(tables);
        Map<String, boolean[]> flagsByTable = fetchRlsFlagsByTable(tables);

        List<RlsViolation> violations = new ArrayList<>();
        for (String table : tables) {
            Set<String> commands = commandsByTable.getOrDefault(table, Set.of());
            boolean fullCommandCoverage = commands.contains("ALL") || commands.containsAll(REQUIRED_COMMANDS);
            boolean[] flags = flagsByTable.getOrDefault(table, new boolean[]{false, false});
            boolean rowSecurityEnabled = flags[0];
            boolean forceEnabled = flags[1];

            List<String> issues = new ArrayList<>();
            if (!fullCommandCoverage) {
                Set<String> missing = new TreeSet<>(REQUIRED_COMMANDS);
                missing.removeAll(commands);
                issues.add("brak polityki dla komend: " + String.join(", ", missing));
            }
            if (!rowSecurityEnabled) {
                issues.add("ENABLE ROW LEVEL SECURITY nie jest włączone (relrowsecurity=false) — polityki "
                        + "istnieją w katalogu, ale nie są wykonywane wcale");
            }
            if (!forceEnabled) {
                issues.add("FORCE ROW LEVEL SECURITY nie jest włączone (relforcerowsecurity=false) — "
                        + "właściciel tabeli omija RLS niezależnie od polityk");
            }
            if (!issues.isEmpty()) {
                violations.add(new RlsViolation(table, issues));
            }
        }
        return violations;
    }

    private Map<String, Set<String>> fetchPolicyCommandsByTable(List<String> tables) {
        Map<String, Set<String>> result = new HashMap<>();
        jdbcTemplate.query(
                "SELECT tablename, cmd FROM pg_policies WHERE schemaname = 'public' AND tablename = ANY(?)",
                (PreparedStatementSetter) ps ->
                        ps.setArray(1, ps.getConnection().createArrayOf("text", tables.toArray())),
                (RowCallbackHandler) rs -> result.computeIfAbsent(rs.getString("tablename"), t -> new HashSet<>())
                        .add(rs.getString("cmd"))
        );
        return result;
    }

    private Map<String, boolean[]> fetchRlsFlagsByTable(List<String> tables) {
        Map<String, boolean[]> result = new HashMap<>();
        jdbcTemplate.query(
                """
                SELECT relname, relrowsecurity, relforcerowsecurity
                FROM pg_class
                WHERE relkind IN ('r', 'p') AND relname = ANY(?)
                """,
                (PreparedStatementSetter) ps ->
                        ps.setArray(1, ps.getConnection().createArrayOf("text", tables.toArray())),
                (RowCallbackHandler) rs -> result.put(rs.getString("relname"),
                        new boolean[]{rs.getBoolean("relrowsecurity"), rs.getBoolean("relforcerowsecurity")})
        );
        return result;
    }

    // =========================================================================
    // 3. Rola połączenia (superuser / BYPASSRLS)
    // =========================================================================

    /** Sprawdza, czy rola bieżącego połączenia ({@code current_user}) omija RLS. */
    RoleBypassInfo checkConnectionRole() {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");
        return new RoleBypassInfo(
                (String) row.get("rolname"),
                Boolean.TRUE.equals(row.get("rolsuper")),
                Boolean.TRUE.equals(row.get("rolbypassrls")));
    }

    // =========================================================================
    // 4. Logowanie naruszeń jako lista, nie jeden zbiorczy WARN
    // =========================================================================

    private void logViolations(List<RlsViolation> violations) {
        if (violations.isEmpty()) {
            log.info("[RlsValidation] Wszystkie tabele klasy TENANT (DB-071) mają pełne pokrycie komend "
                    + "RLS (SELECT/INSERT/UPDATE/DELETE) oraz ENABLE+FORCE ROW LEVEL SECURITY.");
            return;
        }
        for (RlsViolation violation : violations) {
            log.warn("[RlsValidation] Naruszenie RLS – tabela '{}': {}",
                    violation.table(), String.join("; ", violation.issues()));
        }
        log.warn("[RlsValidation] Łącznie {} tabel(a) klasy TENANT (DB-071) z niepełną ochroną RLS. Dane "
                + "mogą być widoczne/modyfikowalne przez wszystkich tenantów pod rolą ograniczoną (bez "
                + "BYPASSRLS) jeśli set_tenant_context() nie zostanie wywołana lub polityka nie obejmuje "
                + "danej komendy.", violations.size());
    }

    /**
     * Loguje rolę połączenia; rzuca {@link IllegalStateException} WYŁĄCZNIE gdy {@link #failOnBypass}
     * jest {@code true} i rola omija RLS — zob. Javadoc klasy.
     */
    private void logOrEnforceRoleInfo(RoleBypassInfo roleInfo) {
        if (!roleInfo.bypassesRls()) {
            log.info("[RlsValidation] Rola połączenia '{}' NIE omija RLS (brak superuser/BYPASSRLS) – "
                    + "powyższa walidacja polityk ma realne znaczenie dla izolacji tenantów.",
                    roleInfo.roleName());
            return;
        }

        String message = String.format(
                "[RlsValidation] Połączenie aplikacji używa roli '%s' z BYPASSRLS/superuser "
                        + "(rolsuper=%s, rolbypassrls=%s) — RLS jest wyłącznie defense-in-depth, główną "
                        + "ochroną przed wyciekiem danych między tenantami jest "
                        + "assertSameTenant/TenantAwareRepository na poziomie aplikacji.",
                roleInfo.roleName(), roleInfo.superuser(), roleInfo.bypassRls());

        if (failOnBypass) {
            throw new IllegalStateException(message + " rls.validation.fail-on-bypass=true wymaga roli "
                    + "połączenia bez BYPASSRLS/superuser — start przerwany.");
        }
        log.warn(message);
    }

    // =========================================================================
    // Typy wynikowe
    // =========================================================================

    /** Jedno naruszenie RLS dla danej tabeli — może zawierać kilka problemów naraz. */
    public record RlsViolation(String table, List<String> issues) {
    }

    /** Wynik sprawdzenia roli bieżącego połączenia pod kątem omijania RLS. */
    public record RoleBypassInfo(String roleName, boolean superuser, boolean bypassRls) {
        public boolean bypassesRls() {
            return superuser || bypassRls;
        }
    }
}
