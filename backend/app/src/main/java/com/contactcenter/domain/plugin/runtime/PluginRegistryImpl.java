package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.ExtensionPoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implementacja rejestru aktywnych instancji pluginów, w pamięci JVM (nie w bazie danych —
 * stan jest odbudowywany przy starcie aplikacji przez ponowne {@code load()} wszystkich
 * {@code enabled=true} instalacji, zob. {@link PluginRuntimeStartupLoader}).
 *
 * <p>Klucz mapy: {@code tenantId + "::" + extensionPoint.name()} → lista uchwytów w porządku
 * rejestracji ({@code display_order}, ARCHITECTURE.md §11.9 — sortowanie po display_order jest
 * zakresem {@code ExtensionPointPublisher}/BE-102; tutaj zachowujemy tylko porządek wstawienia).
 */
@Slf4j
@Service
class PluginRegistryImpl implements PluginRegistry {

    private final Map<String, List<PluginInstanceHandle>> byTenantAndExtensionPoint = new ConcurrentHashMap<>();
    private final ReentrantLock mutationLock = new ReentrantLock();

    @Override
    public void register(PluginInstanceHandle handle, List<ExtensionPoint> extensionPoints) {
        mutationLock.lock();
        try {
            for (ExtensionPoint extensionPoint : extensionPoints) {
                String key = key(handle.tenantId(), extensionPoint);
                List<PluginInstanceHandle> handles =
                        byTenantAndExtensionPoint.computeIfAbsent(key, k -> new ArrayList<>());
                handles.add(handle);
            }
            log.info("[PluginRegistry] Zarejestrowano instalację: tenant={}, pluginKey={}, "
                            + "installationId={}, extensionPoints={}",
                    handle.tenantId(), handle.pluginKey(), handle.installationId(), extensionPoints);
        } finally {
            mutationLock.unlock();
        }
    }

    @Override
    public void unregister(UUID tenantId, UUID installationId) {
        mutationLock.lock();
        try {
            for (Map.Entry<String, List<PluginInstanceHandle>> entry : byTenantAndExtensionPoint.entrySet()) {
                if (!entry.getKey().startsWith(tenantId.toString() + "::")) {
                    continue;
                }
                entry.getValue().removeIf(h -> h.installationId().equals(installationId));
            }
            log.info("[PluginRegistry] Wyrejestrowano instalację: tenant={}, installationId={}",
                    tenantId, installationId);
        } finally {
            mutationLock.unlock();
        }
    }

    @Override
    public List<PluginInstanceHandle> lookup(UUID tenantId, ExtensionPoint extensionPoint) {
        List<PluginInstanceHandle> handles = byTenantAndExtensionPoint.get(key(tenantId, extensionPoint));
        return handles == null ? List.of() : List.copyOf(handles);
    }

    private static String key(UUID tenantId, ExtensionPoint extensionPoint) {
        return tenantId + "::" + extensionPoint.name();
    }
}
