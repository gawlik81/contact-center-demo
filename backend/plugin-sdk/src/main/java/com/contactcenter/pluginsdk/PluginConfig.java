package com.contactcenter.pluginsdk;

import java.util.Optional;

/**
 * Read-only access to the tenant-supplied configuration for a plugin installation, exposed via
 * {@link PluginContext#config()}.
 *
 * <p>Backed by {@code tenant_plugin_installation.installation_config} — values the tenant
 * administrator entered in the admin UI when installing/configuring the plugin (e.g. an
 * external CRM's base URL or API key). Sensitive values are encrypted at rest by the host;
 * this interface only ever exposes already-decrypted plain values to the plugin instance that
 * owns them for the duration of a single invocation.
 */
public interface PluginConfig {

    /**
     * Looks up a configuration value by key.
     *
     * @param key configuration key, as defined by the plugin's own documentation (the platform
     *            does not constrain key names beyond what the manifest/admin UI collects)
     * @return the configured value, or {@link Optional#empty()} if the tenant administrator has
     *         not set a value for this key
     */
    Optional<String> get(String key);

    /**
     * Looks up a configuration value by key, falling back to a default when not configured.
     *
     * @param key          configuration key
     * @param defaultValue value to return when the key is not configured
     * @return the configured value, or {@code defaultValue} if not configured
     */
    String getOrDefault(String key, String defaultValue);
}
