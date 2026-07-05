package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.pluginsdk.PluginLogger;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * Implementacja {@link PluginLogger} — separuje logi pluginu od logów aplikacji
 * (ARCHITECTURE.md §11.6: {@code "writes into plugin_invocation_log, not app logs"}).
 *
 * <p><strong>Zakres tego ticketu (BE-101):</strong> tabela {@code plugin_invocation_log}
 * (DB §11.9) i jej zapis są częścią {@code PluginInvocationLogService} (BE-102) — tutaj
 * logujemy przez SLF4J z dedykowanym markerem/prefiksem {@code [PluginLog]} jako tymczasowy,
 * obserwowalny sink, żeby {@code onActivate}/{@code onDeactivate} miały działający
 * {@link PluginLogger} już teraz. Gdy BE-102 doda {@code PluginInvocationLogService}, ta klasa
 * powinna delegować do niego, nie tylko do SLF4J.
 */
@Slf4j
final class PluginLoggerImpl implements PluginLogger {

    /**
     * Limit długości wiadomości pluginu przed truncacją — mitygacja log-flood (code review
     * BE-101, finding Medium). Pełna naprawa kontraktu SDK (zapis do {@code plugin_invocation_log},
     * nie do logów aplikacji) jest zakresem BE-102.
     */
    private static final int MAX_MESSAGE_LENGTH = 4000;

    private final UUID tenantId;
    private final String pluginKey;

    PluginLoggerImpl(UUID tenantId, String pluginKey) {
        this.tenantId = tenantId;
        this.pluginKey = pluginKey;
    }

    @Override
    public void info(String message) {
        log.info("[PluginLog][tenant={}][plugin={}] {}", tenantId, pluginKey, sanitize(message));
    }

    @Override
    public void warn(String message) {
        log.warn("[PluginLog][tenant={}][plugin={}] {}", tenantId, pluginKey, sanitize(message));
    }

    @Override
    public void error(String message, Throwable throwable) {
        log.error("[PluginLog][tenant={}][plugin={}] {}", tenantId, pluginKey, sanitize(message), throwable);
    }

    /**
     * Mitygacja log-forging/log-flood: escape'uje znaki nowej linii (CR/LF) żeby plugin nie mógł
     * wstrzyknąć fałszywych wpisów logu wyglądających jak osobne linie/zdarzenia, i ucina
     * wiadomość do {@link #MAX_MESSAGE_LENGTH} znaków żeby ograniczyć IO/memory pressure na
     * współdzielony SLF4J sink (TODO(BE-102): zastąpić zapisem do {@code plugin_invocation_log}
     * z właściwym rate-limitingiem/circuit breakerem, nie tylko truncacją).
     */
    private static String sanitize(String message) {
        if (message == null) {
            return null;
        }
        String escaped = message.replace("\r", "\\r").replace("\n", "\\n");
        if (escaped.length() <= MAX_MESSAGE_LENGTH) {
            return escaped;
        }
        return escaped.substring(0, MAX_MESSAGE_LENGTH) + "...[truncated]";
    }
}
