package com.contactcenter.domain.plugin.runtime;

/**
 * Wynik jednej próby wywołania {@code PluginEntryPoint} dla jednej instalacji, na jednej
 * ścieżce dispatchu (ARCHITECTURE.md §11.7, §11.9).
 *
 * <p>Pięć wartości odpowiada 1:1 statusom oczekiwanym przez {@code plugin_invocation_log}
 * (DB-045, CHECK constraint {@code chk_plugin_invocation_log_status}, V077) — reużyty jako
 * jedyna reprezentacja statusu, zarówno w dispatchu ({@code ExtensionPointPublisherImpl}/
 * {@code PluginInvocationConsumer}) jak i w warstwie zapisu/odczytu ({@code
 * PluginInvocationLogService}, BE-105) — bez duplikatu/mapowania na osobny enum encji.
 *
 * <p>Publiczny od BE-105, żeby {@code PluginInvocationLogService} (publiczny interfejs) i
 * {@code PluginInvocationLogDto} (pakiet {@code domain.plugin.dto}) mogły go używać w swoich
 * sygnaturach.
 */
public enum InvocationStatus {
    SUCCESS,
    FAILED,
    TIMED_OUT,
    CIRCUIT_OPEN,
    SKIPPED_DISABLED
}
