package com.contactcenter.domain.plugin.runtime;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Właściwości konfiguracyjne wywołań pluginów — timeouty domyślne per extension point oraz
 * wymiarowanie dedykowanego executora ({@code PluginInvocationExecutor}), ARCHITECTURE.md
 * §11.5/§11.7.
 *
 * <p>Wartości domyślne odpowiadają wymaganiom BE-102: {@code PRE_CONTACT_CONNECT}=2000ms,
 * {@code MANUAL_ACTION}=5000ms — nadpisywalne przez {@code application*.yml}/zmienne ENV pod
 * prefiksem {@code plugin.invocation}. {@link #maxTimeoutMs} odzwierciedla maksimum platformy
 * narzucone przez {@code chk_tenant_plugin_extension_binding_timeout} (V076, DB-044) — żaden
 * skonfigurowany timeout nie powinien przekroczyć tej wartości.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "plugin.invocation")
public class PluginInvocationProperties {

    /** Timeout domyślny dla {@code PRE_CONTACT_CONNECT} (blocking, agent desktop czeka). */
    private long preContactConnectTimeoutMs = 2000L;

    /** Timeout domyślny dla {@code MANUAL_ACTION} (blocking, żądanie agenta). */
    private long manualActionTimeoutMs = 5000L;

    /**
     * Maksimum platformy dla każdego skonfigurowanego timeoutu — zgodne z
     * {@code chk_tenant_plugin_extension_binding_timeout} (V076: {@code timeout_ms <= 60000}).
     */
    private long maxTimeoutMs = 60_000L;

    /** Rozmiar core poola {@code PluginInvocationExecutor}. */
    private int executorCorePoolSize = 8;

    /** Rozmiar max poola {@code PluginInvocationExecutor}. */
    private int executorMaxPoolSize = 32;

    /** Pojemność kolejki zadań {@code PluginInvocationExecutor}. */
    private int executorQueueCapacity = 200;

    /**
     * Zwraca timeout dla {@code PRE_CONTACT_CONNECT}, capped przez {@link #maxTimeoutMs}.
     */
    long effectivePreContactConnectTimeoutMs() {
        return Math.min(preContactConnectTimeoutMs, maxTimeoutMs);
    }

    /**
     * Zwraca timeout dla {@code MANUAL_ACTION}, capped przez {@link #maxTimeoutMs}.
     */
    long effectiveManualActionTimeoutMs() {
        return Math.min(manualActionTimeoutMs, maxTimeoutMs);
    }
}
