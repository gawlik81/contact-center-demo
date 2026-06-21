package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.ExtensionPoint;
import com.contactcenter.pluginsdk.PluginEntryPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PluginRegistryImpl – lookup (tenantId, extensionPoint) -> aktywne instancje")
class PluginRegistryImplTest {

    private PluginRegistryImpl registry;

    @BeforeEach
    void setUp() {
        registry = new PluginRegistryImpl();
    }

    @Test
    @DisplayName("register + lookup zwraca uchwyt dla zadeklarowanego extension point")
    void registerThenLookupReturnsHandle() {
        UUID tenantId = UUID.randomUUID();
        PluginInstanceHandle handle = handle(tenantId, "acme-crm-sync");

        registry.register(handle, List.of(ExtensionPoint.PRE_CONTACT_CONNECT, ExtensionPoint.CUSTOMER_SYNC));

        assertThat(registry.lookup(tenantId, ExtensionPoint.PRE_CONTACT_CONNECT)).containsExactly(handle);
        assertThat(registry.lookup(tenantId, ExtensionPoint.CUSTOMER_SYNC)).containsExactly(handle);
        assertThat(registry.lookup(tenantId, ExtensionPoint.DISPOSITION_SET)).isEmpty();
    }

    @Test
    @DisplayName("lookup dla innego tenanta nie widzi instancji zarejestrowanej dla tenanta A")
    void lookupIsScopedPerTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        PluginInstanceHandle handle = handle(tenantA, "acme-crm-sync");

        registry.register(handle, List.of(ExtensionPoint.PRE_CONTACT_CONNECT));

        assertThat(registry.lookup(tenantB, ExtensionPoint.PRE_CONTACT_CONNECT)).isEmpty();
    }

    @Test
    @DisplayName("unregister usuwa instalację ze wszystkich extension points")
    void unregisterRemovesFromAllExtensionPoints() {
        UUID tenantId = UUID.randomUUID();
        PluginInstanceHandle handle = handle(tenantId, "acme-crm-sync");
        registry.register(handle, List.of(ExtensionPoint.PRE_CONTACT_CONNECT, ExtensionPoint.MANUAL_ACTION));

        registry.unregister(tenantId, handle.installationId());

        assertThat(registry.lookup(tenantId, ExtensionPoint.PRE_CONTACT_CONNECT)).isEmpty();
        assertThat(registry.lookup(tenantId, ExtensionPoint.MANUAL_ACTION)).isEmpty();
    }

    @Test
    @DisplayName("Dwie instalacje różnych pluginów dla tego samego tenanta + extension point są obie zwracane")
    void multipleInstallationsForSameExtensionPoint() {
        UUID tenantId = UUID.randomUUID();
        PluginInstanceHandle handle1 = handle(tenantId, "plugin-one");
        PluginInstanceHandle handle2 = handle(tenantId, "plugin-two");

        registry.register(handle1, List.of(ExtensionPoint.POST_CONTACT_END));
        registry.register(handle2, List.of(ExtensionPoint.POST_CONTACT_END));

        assertThat(registry.lookup(tenantId, ExtensionPoint.POST_CONTACT_END))
                .containsExactly(handle1, handle2);
    }

    private static PluginInstanceHandle handle(UUID tenantId, String pluginKey) {
        PluginEntryPoint entryPoint = Mockito.mock(PluginEntryPoint.class);
        ClassLoader classLoader = Mockito.mock(ClassLoader.class);
        return new PluginInstanceHandle(UUID.randomUUID(), tenantId, pluginKey, entryPoint, classLoader, null);
    }
}
