package com.contactcenter.domain.plugin;

/**
 * Stały, zdefiniowany przez platformę zbiór punktów rozszerzeń (extension points), z jakich
 * plugin może deklarować wsparcie w {@code manifest.extensionPoints}
 * (ARCHITECTURE.md §11.2, §11.5).
 *
 * <p>Każda wartość odpowiada 1:1 nazwie callbacku w {@code PluginEntryPoint} (plugin-sdk,
 * BE-097): {@code onPreContactConnect}, {@code onPostContactEnd}, {@code onCustomerSync},
 * {@code onDispositionSet}, {@code onManualAction}.
 *
 * <p>Manifest deklarujący wartość poza tym enumem jest odrzucany przy walidacji
 * (§11.4 krok 4) — plugin nie może zadeklarować rozszerzenia, którego platforma nie zna.
 */
public enum ExtensionPoint {
    PRE_CONTACT_CONNECT,
    POST_CONTACT_END,
    CUSTOMER_SYNC,
    DISPOSITION_SET,
    MANUAL_ACTION
}
