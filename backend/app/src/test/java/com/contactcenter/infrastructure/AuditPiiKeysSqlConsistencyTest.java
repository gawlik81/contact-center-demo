package com.contactcenter.infrastructure;

import com.contactcenter.infrastructure.aspect.AuditPiiKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test spójności (BE-142, AC): lista kluczy PII w {@link AuditPiiKeys} (Java, maskowanie u źródła
 * w {@code AuditAspect}) MUSI być identyczna z listą w funkcji SQL {@code fn_mask_pii_jsonb_value}
 * (migracja {@code V098__mask_audit_log_pii_on_anonymize_customer.sql}, DB-062/D10) — ta sama treść
 * jest maskowana niezależnie od tego, czy trafia do {@code audit_log} przez {@code AuditAspect}
 * (nowe wpisy) czy jest maskowana wstecznie przez {@code mask_audit_log_pii} przy anonimizacji
 * klienta (wpisy historyczne podmiotu).
 *
 * <p>Test parsuje RZECZYWISTĄ treść pliku migracji z classpath (dostępną w module {@code app} dzięki
 * dodatkowemu katalogowi zasobów {@code ../src/main/resources} skonfigurowanemu w {@code app/pom.xml}
 * dla Flyway) — celowo NIE duplikuje listy jako drugiej, ręcznie utrzymywanej kopii w teście. Taka
 * druga kopia byłaby dokładnie tym, przed czym ten test ma chronić: mogłaby ulec dryfowi względem
 * {@link AuditPiiKeys} albo względem V098 bez żadnego automatycznego sygnału.
 */
@DisplayName("AuditPiiKeys <-> V098 fn_mask_pii_jsonb_value: spójność listy kluczy PII (BE-142 AC)")
class AuditPiiKeysSqlConsistencyTest {

    private static final String MIGRATION_RESOURCE =
            "/db/migration/V098__mask_audit_log_pii_on_anonymize_customer.sql";

    @Test
    @DisplayName("AuditPiiKeys.KEYS == zbiór literałów w ARRAY[...] wewnątrz fn_mask_pii_jsonb_value (V098) -- ten sam zbiór, kolejność nieistotna")
    void auditPiiKeys_matchesSqlFunctionKeyList() throws IOException {
        Set<String> sqlKeys = extractSqlPiiKeys();

        assertThat(sqlKeys)
                .as("lista kluczy sparsowana z V098 nie może być pusta -- inaczej ten test przechodziłby fałszywie (regex nie trafił, a assertion poniżej i tak by się zgodziła na pusty zbiór)")
                .isNotEmpty();
        assertThat(AuditPiiKeys.KEYS)
                .as("AuditPiiKeys (Java) musi zawierać DOKŁADNIE te same klucze co fn_mask_pii_jsonb_value (SQL, V098) -- rozjazd oznacza, że część PII przecieka przez jeden z dwóch mechanizmów maskowania")
                .containsExactlyInAnyOrderElementsOf(sqlKeys);
    }

    private Set<String> extractSqlPiiKeys() throws IOException {
        String sql = readMigrationFile();

        Matcher functionBlock = Pattern.compile(
                        "FOREACH\\s+v_key\\s+IN\\s+ARRAY\\s+ARRAY\\[(.*?)]",
                        Pattern.DOTALL)
                .matcher(sql);
        assertThat(functionBlock.find())
                .as("nie znaleziono bloku 'FOREACH v_key IN ARRAY ARRAY[...]' w V098 -- migracja "
                        + "została zmieniona w sposób, który łamie ten test parsujący. NIE edytuj "
                        + "V098 (poza zakresem backend-dev-expert, BE-142) -- jeśli widzisz ten błąd, "
                        + "zgłoś rozjazd zamiast poprawiać migrację")
                .isTrue();

        Set<String> keys = new LinkedHashSet<>();
        Matcher keyLiteral = Pattern.compile("'([a-zA-Z0-9_]+)'").matcher(functionBlock.group(1));
        while (keyLiteral.find()) {
            keys.add(keyLiteral.group(1));
        }
        return keys;
    }

    private String readMigrationFile() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(MIGRATION_RESOURCE)) {
            assertThat(in)
                    .as("migracja %s nie jest na classpath testów -- sprawdź backend/app/pom.xml "
                            + "(zasób '../src/main/resources' używany też przez Flyway)", MIGRATION_RESOURCE)
                    .isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
