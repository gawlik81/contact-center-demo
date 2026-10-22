package com.contactcenter.infrastructure.config;

import com.contactcenter.support.PostgresTestDatabase;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test integracyjny {@link RlsValidationService} na PRAWDZIWYM PostgreSQL (Testcontainers, pełny
 * łańcuch Flyway) — BE-138, EPIC-30.
 *
 * <p><strong>Stan DB-074 w chwili pisania tego testu (2026-10-08):</strong> {@code db-schema-architect}
 * domyka RÓWNOLEGLE (w tej samej gałęzi, nie w osobnym commicie) brakujące polityki/FORCE dla tabel
 * klasy TENANT z niepełnym pokryciem (DB-071 klasa B: {@code contact}, {@code campaign},
 * {@code customer}, {@code queue}, {@code ivr_tree}) i klasy TENANT bez RLS wcale (DB-071 klasa D:
 * {@code email_routing_rule}, {@code email_template}, {@code ivr_audio}, {@code contacts_dw}).
 * Migracje {@code V113}-{@code V117} już wylądowały w chwili pisania tego testu i domykają
 * {@code email_routing_rule}/{@code email_template}/{@code ivr_audio}/{@code contacts_dw}/{@code contact};
 * {@code campaign}/{@code customer}/{@code queue}/{@code ivr_tree} mogą wylądować PÓŹNIEJ (ta klasa
 * testowa nie czeka na nie i nie zakłada konkretnego momentu). Dlatego testy w tej klasie NIE
 * hardkodują "zero naruszeń" dla całego schematu — {@link #commandCoverageViolations_matchesIndependentlyComputedLiveCatalogState()}
 * liczy oczekiwany wynik z ŻYWEGO stanu {@code pg_policies}/{@code pg_class} w chwili uruchomienia
 * testu (ten sam mechanizm, inne zapytanie niż serwis — wykrywa regresję logiki, niezależnie od tego,
 * czy DB-074 wylądowało w całości), a {@link #commandCoverageViolations_onlyKnownDb074PendingTablesMayViolate()}
 * pilnuje, że naruszenia nie "wyciekają" na tabele POZA znanym zakresem DB-074 (prawdziwa regresja gdzie
 * indziej). Testy negatywny/pozytywny ({@link #findCommandCoverageViolations_selectOnlyPolicy_reportsAllGaps()},
 * {@link #findCommandCoverageViolations_fullCoverageWithForce_noViolation()}) używają tabel
 * utworzonych WEWNĄTRZ tego testu — w pełni niezależnych od stanu reszty schematu i od czasowania
 * równoległego agenta.
 */
@DisplayName("RlsValidationService – prawdziwa baza, pełny łańcuch Flyway (BE-138)")
class RlsValidationServiceIntegrationTest {

    private static HikariDataSource pool;
    private static JdbcTemplate jdbc;
    private static RlsValidationService service;

    @BeforeAll
    static void startContext() {
        pool = PostgresTestDatabase.superuserPool(2);
        jdbc = new JdbcTemplate(pool);
        service = new RlsValidationService(jdbc);
    }

    @AfterAll
    static void stopContext() {
        pool.close();
    }

    @AfterEach
    void resetFailOnBypass() {
        ReflectionTestUtils.setField(service, "failOnBypass", false);
    }

    // =========================================================================
    // 1. findTenantClassTables – zapytanie SQL, nie twarda lista
    // =========================================================================

    @Test
    @DisplayName("findTenantClassTables: zawiera stabilne tabele klasy TENANT, wyklucza GLOBAL/MIXED/partycje potomne")
    void findTenantClassTables_excludesGlobalAndMixedAndPartitionChildren() {
        List<String> tables = service.findTenantClassTables();

        // Klasa TENANT, pełne pokrycie od dawna (DB-071 klasa A) – stabilne niezależnie od DB-074.
        assertThat(tables).contains(
                "agent_break", "email_message", "social_message", "contact_event",
                "contact_ai_summary", "phone_number", "tenant_twilio_config", "scheduled_callback");

        // Klasa GLOBAL (DB-071) – tenant_id NOT NULL, ale świadomie bez izolacji RLS.
        assertThat(tables).doesNotContain("tenant", "plugin_version");

        // Klasa MIXED (DB-071) – tenant_id nullable (NULL = rekord globalny), wykluczona naturalnie
        // przez is_nullable = 'NO', nie przez nazwę.
        assertThat(tables).doesNotContain("audit_log", "app_user", "refresh_token", "gdpr_processing_register");

        // Partycje potomne (np. email_message_2026_01, contact_event_2026_01...) nie mają własnej
        // kolumny widocznej jako odrębna tabela TENANT – są wykluczone przez pg_inherits.
        List<String> partitionChildren = jdbc.queryForList(
                """
                SELECT c.relname FROM pg_inherits i
                JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent
                WHERE p.relname IN ('email_message', 'social_message', 'contact', 'contact_event',
                                     'contact_transcription', 'contact_ai_summary', 'audit_log',
                                     'plugin_invocation_log', 'campaign_contact')
                """, String.class);
        assertThat(partitionChildren).isNotEmpty(); // sanity check – schemat jest faktycznie partycjonowany
        assertThat(tables).doesNotContainAnyElementsOf(partitionChildren);
    }

    // =========================================================================
    // 2. Pokrycie komend + FORCE na PRAWDZIWYM, ewoluującym schemacie
    // =========================================================================

    @Test
    @DisplayName("findCommandCoverageViolations: wynik dla żywego schematu zgadza się z niezależnie przeliczonym stanem pg_policies/pg_class")
    void commandCoverageViolations_matchesIndependentlyComputedLiveCatalogState() {
        List<String> tenantTables = service.findTenantClassTables();
        assertThat(tenantTables).isNotEmpty();

        List<RlsValidationService.RlsViolation> actual = service.findCommandCoverageViolations(tenantTables);
        Set<String> actualViolatingTables = actual.stream()
                .map(RlsValidationService.RlsViolation::table)
                .collect(Collectors.toSet());

        Set<String> expectedViolatingTables = independentlyComputeViolatingTables(tenantTables);

        assertThat(actualViolatingTables)
                .as("naruszenia wyliczone przez serwis muszą się zgadzać z osobno przeliczonym stanem "
                        + "pg_policies/pg_class (ta sama metodologia, inne zapytanie – wykrywa regresję "
                        + "logiki serwisu niezależnie od stanu DB-074)")
                .isEqualTo(expectedViolatingTables);
    }

    @Test
    @DisplayName("findCommandCoverageViolations: naruszenia na żywym schemacie ograniczają się do znanego zakresu DB-074")
    void commandCoverageViolations_onlyKnownDb074PendingTablesMayViolate() {
        // Pełny zakres DB-074 (klasy TENANT, nie MIXED – audit_log/app_user są MIXED i nie są w ogóle
        // zwracane przez findTenantClassTables, więc nie mogą się tu pojawić) – patrz briefing BE-138.
        // W chwili PISANIA tego testu DB-074 wylądowało w CAŁOŚCI (V113-V124, łącznie z audit_log/
        // app_user/scheduled_callback) – te 9 nazw zostają tu jako dokumentacja oryginalnego zakresu i
        // zabezpieczenie, gdyby test uruchomiono na checkout-cie PRZED pełnym wdrożeniem DB-074.
        //
        // ODKRYCIE BE-138 (nie było częścią DB-071/DB-074): 7 tabel klasy TENANT A z DB-071 ("pełne
        // pokrycie komend") NIE MA ustawionego FORCE ROW LEVEL SECURITY – DB-071 sprawdzał wyłącznie
        // pokrycie KOMEND (SELECT/INSERT/UPDATE/DELETE w pg_policies), nie relforcerowsecurity, więc ta
        // luka nigdy nie była raportowana. Pod rolą BEZ BYPASSRLS, która jest WŁAŚCICIELEM tabeli
        // (typowe dla roli migracyjnej Flyway) RLS byłby całkowicie omijany na tych 7 tabelach, mimo
        // kompletnych polityk. Potwierdzone bezpośrednio w pg_class (ta migracja ich NIGDY nie ustawia
        // FORCE, w odróżnieniu od np. V099 dla email_message/social_message): agent_break, agent_group,
        // phone_number, phone_routing_rule, scheduled_callback (FORCE brak mimo V124 naprawiającej
        // osobny problem – arność GUC), social_integration, tenant_twilio_config.
        // NIE jest to w zakresie BE-138 (walidacja, nie migracja) ani DB-074 (inny zakres) – wymaga
        // osobnego ticketu DB (np. "DB-XXX – FORCE ROW LEVEL SECURITY na 7 tabelach klasy TENANT A").
        // Do czasu tej migracji ten zbiór jest ZNANYM, udokumentowanym stanem, nie regresją testu.
        Set<String> allowedToStillViolate = Set.of(
                "contact", "campaign", "customer", "queue", "ivr_tree",
                "email_routing_rule", "email_template", "ivr_audio", "contacts_dw",
                "agent_break", "agent_group", "phone_number", "phone_routing_rule",
                "scheduled_callback", "social_integration", "tenant_twilio_config");

        List<String> tenantTables = service.findTenantClassTables();
        List<RlsValidationService.RlsViolation> violations = service.findCommandCoverageViolations(tenantTables);
        Set<String> violatingTables = violations.stream()
                .map(RlsValidationService.RlsViolation::table)
                .collect(Collectors.toSet());

        assertThat(violatingTables)
                .as("naruszenie na tabeli POZA znanym zakresem DB-074 oznacza prawdziwą regresję polityk "
                        + "RLS (nie stan przejściowy) – sprawdź pg_policies/pg_class dla tej tabeli")
                .isSubsetOf(allowedToStillViolate);
    }

    private Set<String> independentlyComputeViolatingTables(List<String> tables) {
        Set<String> violating = new HashSet<>();
        for (String table : tables) {
            List<String> cmds = jdbc.queryForList(
                    "SELECT cmd FROM pg_policies WHERE schemaname = 'public' AND tablename = ?",
                    String.class, table);
            boolean fullCoverage = cmds.contains("ALL")
                    || Set.copyOf(cmds).containsAll(Set.of("SELECT", "INSERT", "UPDATE", "DELETE"));

            Map<String, Object> flags = jdbc.queryForMap(
                    "SELECT relrowsecurity, relforcerowsecurity FROM pg_class "
                            + "WHERE relkind IN ('r', 'p') AND relname = ?", table);
            boolean rowSecurity = Boolean.TRUE.equals(flags.get("relrowsecurity"));
            boolean force = Boolean.TRUE.equals(flags.get("relforcerowsecurity"));

            if (!fullCoverage || !rowSecurity || !force) {
                violating.add(table);
            }
        }
        return violating;
    }

    // =========================================================================
    // 3. Test negatywny / pozytywny na tabelach utworzonych WEWNĄTRZ tego testu
    // =========================================================================

    @Test
    @DisplayName("findCommandCoverageViolations: tabela z polityką TYLKO-SELECT i bez FORCE -> naruszenie raportuje wszystkie braki")
    void findCommandCoverageViolations_selectOnlyPolicy_reportsAllGaps() {
        String table = "be138_test_select_only_" + shortId();
        jdbc.execute("CREATE TABLE " + table + " (id uuid PRIMARY KEY, tenant_id uuid NOT NULL)");
        try {
            jdbc.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
            jdbc.execute("CREATE POLICY " + table + "_select ON " + table
                    + " FOR SELECT USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)");
            // Celowo BEZ "ALTER TABLE ... FORCE ROW LEVEL SECURITY".

            List<RlsValidationService.RlsViolation> violations =
                    service.findCommandCoverageViolations(List.of(table));

            assertThat(violations).hasSize(1);
            RlsValidationService.RlsViolation violation = violations.get(0);
            assertThat(violation.table()).isEqualTo(table);
            assertThat(violation.issues()).anySatisfy(issue ->
                    assertThat(issue).contains("brak polityki dla komend")
                            .contains("DELETE").contains("INSERT").contains("UPDATE")
                            .doesNotContain("SELECT"));
            assertThat(violation.issues()).anySatisfy(issue ->
                    assertThat(issue).contains("FORCE ROW LEVEL SECURITY nie jest włączone"));
            assertThat(violation.issues()).noneSatisfy(issue ->
                    assertThat(issue).contains("ENABLE ROW LEVEL SECURITY nie jest włączone"));
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    @DisplayName("findCommandCoverageViolations: tabela bez ENABLE ROW LEVEL SECURITY wcale -> naruszenie zgłasza brak ENABLE i wszystkich komend")
    void findCommandCoverageViolations_noRlsAtAll_reportsRowSecurityAndAllCommandsMissing() {
        String table = "be138_test_no_rls_" + shortId();
        jdbc.execute("CREATE TABLE " + table + " (id uuid PRIMARY KEY, tenant_id uuid NOT NULL)");
        try {
            // Brak ENABLE ROW LEVEL SECURITY i brak jakiejkolwiek polityki – odtwarza stan tabel
            // klasy D z DB-071 (campaign_contact/email_routing_rule/... przed naprawą).

            List<RlsValidationService.RlsViolation> violations =
                    service.findCommandCoverageViolations(List.of(table));

            assertThat(violations).hasSize(1);
            RlsValidationService.RlsViolation violation = violations.get(0);
            assertThat(violation.issues()).anySatisfy(issue -> assertThat(issue)
                    .contains("brak polityki dla komend")
                    .contains("SELECT").contains("INSERT").contains("UPDATE").contains("DELETE"));
            assertThat(violation.issues()).anySatisfy(issue ->
                    assertThat(issue).contains("ENABLE ROW LEVEL SECURITY nie jest włączone"));
            assertThat(violation.issues()).anySatisfy(issue ->
                    assertThat(issue).contains("FORCE ROW LEVEL SECURITY nie jest włączone"));
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    @DisplayName("findCommandCoverageViolations: polityka ALL + ENABLE+FORCE -> brak naruszenia")
    void findCommandCoverageViolations_fullCoverageWithForce_noViolation() {
        String table = "be138_test_full_coverage_" + shortId();
        jdbc.execute("CREATE TABLE " + table + " (id uuid PRIMARY KEY, tenant_id uuid NOT NULL)");
        try {
            jdbc.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
            jdbc.execute("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY");
            jdbc.execute("CREATE POLICY " + table + "_all ON " + table
                    + " FOR ALL USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)"
                    + " WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true)::uuid)");

            List<RlsValidationService.RlsViolation> violations =
                    service.findCommandCoverageViolations(List.of(table));

            assertThat(violations).isEmpty();
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    @Test
    @DisplayName("findCommandCoverageViolations: komplet czterech OSOBNYCH polityk (bez ALL) + ENABLE+FORCE -> brak naruszenia")
    void findCommandCoverageViolations_fourSeparatePolicies_noViolation() {
        String table = "be138_test_four_policies_" + shortId();
        jdbc.execute("CREATE TABLE " + table + " (id uuid PRIMARY KEY, tenant_id uuid NOT NULL)");
        try {
            jdbc.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
            jdbc.execute("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY");
            String tenantExpr = "tenant_id = current_setting('app.current_tenant_id', true)::uuid";
            jdbc.execute("CREATE POLICY " + table + "_select ON " + table + " FOR SELECT USING (" + tenantExpr + ")");
            jdbc.execute("CREATE POLICY " + table + "_insert ON " + table + " FOR INSERT WITH CHECK (" + tenantExpr + ")");
            jdbc.execute("CREATE POLICY " + table + "_update ON " + table + " FOR UPDATE USING (" + tenantExpr + ") WITH CHECK (" + tenantExpr + ")");
            jdbc.execute("CREATE POLICY " + table + "_delete ON " + table + " FOR DELETE USING (" + tenantExpr + ")");

            List<RlsValidationService.RlsViolation> violations =
                    service.findCommandCoverageViolations(List.of(table));

            assertThat(violations).isEmpty();
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    // =========================================================================
    // 4. Rola połączenia (Testcontainers łączy się superuserem -> omija RLS)
    // =========================================================================

    @Test
    @DisplayName("checkConnectionRole: superuser cc_test -> bypassesRls() = true")
    void checkConnectionRole_superuserConnection_bypassesRls() {
        RlsValidationService.RoleBypassInfo roleInfo = service.checkConnectionRole();

        assertThat(roleInfo.superuser()).isTrue();
        assertThat(roleInfo.bypassesRls()).isTrue();
    }

    @Test
    @DisplayName("checkConnectionRole: rola LOGIN bez BYPASSRLS/superuser (członek app_user) -> bypassesRls() = false")
    void checkConnectionRole_restrictedRole_doesNotBypassRls() {
        String roleName = "cc_be138_restricted_" + shortId();
        String password = PostgresTestDatabase.createRestrictedLoginRole(jdbc, roleName);
        HikariDataSource restrictedPool = PostgresTestDatabase.pool(roleName, password, 1);
        try {
            JdbcTemplate restrictedJdbc = new JdbcTemplate(restrictedPool);
            RlsValidationService restrictedService = new RlsValidationService(restrictedJdbc);

            RlsValidationService.RoleBypassInfo roleInfo = restrictedService.checkConnectionRole();

            assertThat(roleInfo.superuser()).isFalse();
            assertThat(roleInfo.bypassRls()).isFalse();
            assertThat(roleInfo.bypassesRls()).isFalse();
        } finally {
            restrictedPool.close();
        }
    }

    // =========================================================================
    // 5. validateRlsPolicies – orkiestracja + flaga rls.validation.fail-on-bypass
    // =========================================================================

    @Test
    @DisplayName("validateRlsPolicies: fail-on-bypass=false (domyślnie) -> nie rzuca, mimo roli superuser")
    void validateRlsPolicies_failOnBypassFalse_doesNotThrow() {
        ReflectionTestUtils.setField(service, "failOnBypass", false);

        assertThatCode(service::validateRlsPolicies).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("validateRlsPolicies: fail-on-bypass=true + rola superuser/BYPASSRLS -> rzuca IllegalStateException i przerywa start")
    void validateRlsPolicies_failOnBypassTrue_throwsWhenRoleBypassesRls() {
        ReflectionTestUtils.setField(service, "failOnBypass", true);

        assertThatThrownBy(service::validateRlsPolicies)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BYPASSRLS")
                .hasMessageContaining("rls.validation.fail-on-bypass=true");
    }

    @Test
    @DisplayName("validateRlsPolicies: fail-on-bypass=true + rola BEZ BYPASSRLS -> nie rzuca")
    void validateRlsPolicies_failOnBypassTrue_doesNotThrowWhenRoleDoesNotBypassRls() {
        String roleName = "cc_be138_restricted2_" + shortId();
        String password = PostgresTestDatabase.createRestrictedLoginRole(jdbc, roleName);
        HikariDataSource restrictedPool = PostgresTestDatabase.pool(roleName, password, 1);
        try {
            JdbcTemplate restrictedJdbc = new JdbcTemplate(restrictedPool);
            RlsValidationService restrictedService = new RlsValidationService(restrictedJdbc);
            ReflectionTestUtils.setField(restrictedService, "failOnBypass", true);

            assertThatCode(restrictedService::validateRlsPolicies).doesNotThrowAnyException();
        } finally {
            restrictedPool.close();
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
