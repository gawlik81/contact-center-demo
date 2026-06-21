package com.contactcenter.domain.plugin.runtime;

/**
 * Wynik jednej próby wywołania {@code PluginEntryPoint} dla jednej instalacji, na jednej
 * ścieżce dispatchu (ARCHITECTURE.md §11.7, §11.9).
 *
 * <p>Pięć wartości odpowiada 1:1 statusom oczekiwanym przez {@code plugin_invocation_log}
 * (DB-045) — ten enum jest tymczasowym, lokalnym odpowiednikiem w {@code domain.plugin.runtime}
 * do czasu, aż BE-105 doda realny {@code PluginInvocationLogService}/encję z własnym statusem
 * (prawdopodobnie tę samą reprezentację — zob. notatka w {@link ExtensionPointPublisherImpl}).
 */
enum InvocationStatus {
    SUCCESS,
    FAILED,
    TIMED_OUT,
    CIRCUIT_OPEN,
    SKIPPED_DISABLED
}
