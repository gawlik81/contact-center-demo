package com.contactcenter.domain.plugin;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Walidacja stringów uprawnień (`manifest.permissions`) względem stałego zbioru
 * dozwolonych przez platformę kategorii (ARCHITECTURE.md §11.2, §11.6).
 *
 * <p>Uprawnienia NIE są prostym enumem 1:1 — większość to dokładne stringi z zamkniętego
 * zbioru ({@code "customer:read"}, {@code "contact:read"}, ...), ale kategoria
 * {@code http:egress:<host>} ma dowolny host jako suffix (egress allow-list jest
 * wymuszany w runtime przez BE-101, nie w tym tickecie — tu tylko sprawdzamy kształt
 * stringa, by manifest nie mógł zadeklarować nieznanej kategorii uprawnień).
 *
 * <p>Zbiór jest świadomie minimalny i odpowiada czterem punktom integracji uzgodnionym
 * dla EPIC-28 (PRE_CONTACT_CONNECT/CUSTOMER_SYNC potrzebują odczytu/zapisu klienta i
 * kontaktu, MANUAL_ACTION/CUSTOMER_SYNC potrzebują egress HTTP do zewnętrznego systemu).
 */
final class PluginPermission {

    /** Dozwolone dokładne stringi uprawnień (poza kategorią z prefiksem {@code http:egress:}). */
    static final Set<String> EXACT_PERMISSIONS = Set.of(
            "customer:read",
            "customer:update",
            "contact:read",
            "contact:update"
    );

    private static final String HTTP_EGRESS_PREFIX = "http:egress:";

    /**
     * Host musi być niepusty i nie zawierać białych znaków / ścieżki — to tylko hostname
     * lub hostname:port, walidacja kształtu, nie pełna walidacja DNS.
     */
    private static final Pattern HTTP_EGRESS_HOST_PATTERN =
            Pattern.compile("^[a-zA-Z0-9.\\-]+(:\\d{1,5})?$");

    private PluginPermission() {
    }

    /**
     * Sprawdza, czy dany string uprawnienia jest rozpoznawany przez platformę.
     *
     * @param permission wartość z {@code manifest.permissions}
     * @return {@code true} jeśli jest to dokładne dozwolone uprawnienie lub
     *         {@code http:egress:<host>} z poprawnym kształtem hosta
     */
    static boolean isAllowed(String permission) {
        if (permission == null || permission.isBlank()) {
            return false;
        }
        if (EXACT_PERMISSIONS.contains(permission)) {
            return true;
        }
        if (permission.startsWith(HTTP_EGRESS_PREFIX)) {
            String host = permission.substring(HTTP_EGRESS_PREFIX.length());
            return HTTP_EGRESS_HOST_PATTERN.matcher(host).matches();
        }
        return false;
    }
}
