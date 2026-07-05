package com.contactcenter.domain.plugin.runtime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testy {@link PluginClassLoader} — isolamento per (tenant_id, plugin_key)
 * (ARCHITECTURE.md §11.3, kryterium akceptacji BE-101 #2: dwa tenanty instalujące ten sam
 * pluginKey/pluginVersionId dostają dwa różne obiekty {@code ClassLoader}).
 */
@DisplayName("PluginClassLoader – jedna instancja per (tenant_id, plugin_key)")
class PluginClassLoaderTest {

    private final List<Path> jarsToCleanup = new ArrayList<>();
    private final List<URLClassLoader> loadersToClose = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (URLClassLoader loader : loadersToClose) {
            try {
                loader.close();
            } catch (IOException ignored) {
                // best-effort cleanup w teście
            }
        }
        for (Path jar : jarsToCleanup) {
            try {
                Files.deleteIfExists(jar);
            } catch (IOException ignored) {
                // best-effort cleanup w teście
            }
        }
    }

    @Test
    @DisplayName("KRYTERIUM AKCEPTACJI: dwa tenanty z tym samym JAR-em dostają różne instancje ClassLoader")
    void twoTenantsWithSameJarGetDifferentClassLoaderInstances() throws IOException {
        Path jarPath = registerJar(RuntimeTestPluginBuilder.buildMinimalJar("com.acme.SharedPlugin"));

        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        PluginClassLoader loaderForTenantA = register(new PluginClassLoader(jarPath, tenantA, "acme-crm-sync"));
        PluginClassLoader loaderForTenantB = register(new PluginClassLoader(jarPath, tenantB, "acme-crm-sync"));

        assertThat(loaderForTenantA).isNotSameAs(loaderForTenantB);
    }

    @Test
    @DisplayName("Klasy załadowane przez różne instancje (tenant A vs B) z tego samego JAR-a są różnymi obiektami Class")
    void classesLoadedByDifferentInstancesAreDistinctClassObjects() throws Exception {
        Path jarPath = registerJar(RuntimeTestPluginBuilder.buildMinimalJar("com.acme.SharedPlugin2"));

        PluginClassLoader loaderA = register(new PluginClassLoader(jarPath, UUID.randomUUID(), "acme-crm-sync"));
        PluginClassLoader loaderB = register(new PluginClassLoader(jarPath, UUID.randomUUID(), "acme-crm-sync"));

        Class<?> classFromA = loaderA.loadClass("com.acme.SharedPlugin2");
        Class<?> classFromB = loaderB.loadClass("com.acme.SharedPlugin2");

        // Identyczny bytecode, ale classloader różny => Class objects różne (no shared static state,
        // ARCHITECTURE.md §11.3 "no shared static state").
        assertThat(classFromA).isNotEqualTo(classFromB);
    }

    @Test
    @DisplayName("Parent classloadera to PlatformApiClassLoader.INSTANCE, nigdy classloader aplikacji")
    void parentIsAlwaysPlatformApiClassLoader() throws IOException {
        Path jarPath = registerJar(RuntimeTestPluginBuilder.buildMinimalJar("com.acme.ParentCheckPlugin"));
        PluginClassLoader loader = register(new PluginClassLoader(jarPath, UUID.randomUUID(), "acme-crm-sync"));

        assertThat(loader.getParent()).isSameAs(PlatformApiClassLoader.INSTANCE);
    }

    @Test
    @DisplayName("Klasa pluginu nie może odnaleźć klas domenowych aplikacji przez ten classloader")
    void pluginCannotReachApplicationClasses() throws IOException {
        Path jarPath = registerJar(RuntimeTestPluginBuilder.buildMinimalJar("com.acme.IsolationCheckPlugin"));
        PluginClassLoader loader = register(new PluginClassLoader(jarPath, UUID.randomUUID(), "acme-crm-sync"));

        assertThatThrownBy(() ->
                Class.forName("com.contactcenter.domain.customer.CustomerServiceImpl", true, loader))
                .isInstanceOf(ClassNotFoundException.class);
    }

    private Path registerJar(Path jarPath) {
        jarsToCleanup.add(jarPath);
        return jarPath;
    }

    private PluginClassLoader register(PluginClassLoader loader) {
        loadersToClose.add(loader);
        return loader;
    }
}
