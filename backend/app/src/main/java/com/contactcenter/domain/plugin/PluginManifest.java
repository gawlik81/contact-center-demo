package com.contactcenter.domain.plugin;

import java.util.List;

/**
 * Sparsowana reprezentacja {@code META-INF/plugin-manifest.json} (ARCHITECTURE.md §11.2).
 *
 * <p>Niemutowalny record — odzwierciedla strukturę manifestu 1:1, bez logiki biznesowej.
 * Walidacja semantyczna (JSON Schema, podzbiór enumów platformy, checksum) odbywa się
 * w {@link PluginValidationServiceImpl}, nie tutaj.
 *
 * @param pluginKey        unikalny identyfikator pluginu w katalogu, np. {@code "acme-crm-sync"}
 * @param displayName      nazwa wyświetlana w panelu administracyjnym
 * @param version          wersja semver pluginu, np. {@code "1.3.0"}
 * @param vendor           nazwa dostawcy
 * @param vendorContact    kontakt dostawcy (e-mail), opcjonalny
 * @param sdkVersion       wersja SDK, względem której plugin został skompilowany, np. {@code "1.x"}
 * @param entryPointClass  pełna nazwa klasy implementującej {@code PluginEntryPoint} z plugin-sdk
 * @param extensionPoints  deklarowane punkty rozszerzeń, muszą być podzbiorem {@link ExtensionPoint}
 * @param permissions      deklarowane uprawnienia, muszą być rozpoznawane przez {@link PluginPermission}
 * @param uiPanels         deklarowane panele UI agenta (opcjonalne, może być pustą listą)
 * @param manualActions    deklarowane akcje manualne agenta (opcjonalne, może być pustą listą)
 * @param checksumSha256   SHA-256 zawartości JAR-a w postaci hex, liczony przez build tool dostawcy
 */
record PluginManifest(
        String pluginKey,
        String displayName,
        String version,
        String vendor,
        String vendorContact,
        String sdkVersion,
        String entryPointClass,
        List<String> extensionPoints,
        List<String> permissions,
        List<UiPanel> uiPanels,
        List<ManualAction> manualActions,
        String checksumSha256
) {

    /**
     * Panel UI montowany w agent desktop (np. side panel pluginu).
     *
     * @param panelId    unikalny identyfikator panelu w ramach pluginu
     * @param mountPoint miejsce montowania w UI, np. {@code "AGENT_DESKTOP_SIDE_PANEL"}
     * @param url        adres źródła panelu (np. {@code "classpath:/plugin-ui/index.html"})
     * @param sandbox    atrybut sandboxu iframe, np. {@code "allow-scripts"}
     */
    record UiPanel(String panelId, String mountPoint, String url, String sandbox) {
    }

    /**
     * Akcja manualna inicjowana przez agenta (np. przycisk w toolbarze).
     *
     * @param actionId   unikalny identyfikator akcji w ramach pluginu
     * @param label      etykieta wyświetlana agentowi
     * @param mountPoint miejsce montowania w UI, np. {@code "AGENT_DESKTOP_TOOLBAR"}
     */
    record ManualAction(String actionId, String label, String mountPoint) {
    }
}
