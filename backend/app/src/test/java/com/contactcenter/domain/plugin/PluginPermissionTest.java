package com.contactcenter.domain.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testy {@link PluginPermission#isAllowed(String)} — w szczególności nowej kategorii
 * {@code db:egress:<host>:<port>}, gdzie w przeciwieństwie do {@code http:egress:<host>} port
 * jest OBOWIĄZKOWY (baza zawsze wymaga explicit portu, brak sensownego defaultu uniwersalnego
 * dla wszystkich silników JDBC).
 */
@DisplayName("PluginPermission.isAllowed")
class PluginPermissionTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "customer:read",
            "customer:update",
            "contact:read",
            "contact:update",
            "http:egress:api.acme-crm.example",
            "http:egress:api.acme-crm.example:8443",
    })
    @DisplayName("Uprawnienia z istniejącego, dotychczas dozwolonego zbioru wciąż przechodzą")
    void preExistingPermissionsStillAllowed(String permission) {
        assertThat(PluginPermission.isAllowed(permission)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "db:egress:db.acme-crm.example:5432",
            "db:egress:127.0.0.1:5432",
            "db:egress:localhost:1521",
    })
    @DisplayName("db:egress:<host>:<port> z poprawnym host:port jest dozwolone")
    void dbEgressWithHostAndPortIsAllowed(String permission) {
        assertThat(PluginPermission.isAllowed(permission)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "db:egress:db.acme-crm.example",      // brak portu — niedozwolone (w przeciwieństwie do http)
            "db:egress:",                          // brak host:port
            "db:egress::5432",                     // brak hosta
            "db:egress:db.acme-crm.example:",      // brak portu po dwukropku
            "db:egress:db.acme-crm.example:99999999", // port poza zakresem 1-5 cyfr
    })
    @DisplayName("db:egress:<host> bez portu (lub z niekompletnym host:port) jest odrzucone")
    void dbEgressWithoutPortIsRejected(String permission) {
        assertThat(PluginPermission.isAllowed(permission)).isFalse();
    }

    @Test
    @DisplayName("Nieznana kategoria uprawnienia jest odrzucona")
    void unknownPermissionIsRejected() {
        assertThat(PluginPermission.isAllowed("filesystem:read")).isFalse();
    }

    @Test
    @DisplayName("null/blank są odrzucone")
    void nullOrBlankIsRejected() {
        assertThat(PluginPermission.isAllowed(null)).isFalse();
        assertThat(PluginPermission.isAllowed("")).isFalse();
        assertThat(PluginPermission.isAllowed("   ")).isFalse();
    }
}
