package com.contactcenter.domain.plugin.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testy {@link PlatformApiClassLoader} — wąski classloader eksponujący wyłącznie
 * {@code com.contactcenter.pluginsdk.*} (ARCHITECTURE.md §11.3, kryterium akceptacji BE-101 #1).
 */
@DisplayName("PlatformApiClassLoader – izolacja do pakietu com.contactcenter.pluginsdk")
class PlatformApiClassLoaderTest {

    @Test
    @DisplayName("Klasa z plugin-sdk (com.contactcenter.pluginsdk.*) jest ładowalna")
    void loadsSdkClass() throws ClassNotFoundException {
        Class<?> clazz = PlatformApiClassLoader.INSTANCE.loadClass("com.contactcenter.pluginsdk.PluginContext");

        assertThat(clazz).isNotNull();
        assertThat(clazz).isEqualTo(com.contactcenter.pluginsdk.PluginContext.class);
    }

    @Test
    @DisplayName("Klasa modelu SDK (subpakiet .model) jest ładowalna")
    void loadsSdkModelSubpackageClass() throws ClassNotFoundException {
        Class<?> clazz = PlatformApiClassLoader.INSTANCE.loadClass("com.contactcenter.pluginsdk.model.CustomerView");

        assertThat(clazz).isNotNull();
    }

    @Test
    @DisplayName("KRYTERIUM AKCEPTACJI: Class.forName na klasę domain.* aplikacji rzuca ClassNotFoundException")
    void rejectsApplicationDomainClass() {
        assertThatThrownBy(() ->
                Class.forName("com.contactcenter.domain.tenant.TenantServiceImpl", true, PlatformApiClassLoader.INSTANCE))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("Class.forName na inną klasę domenową (CustomerServiceImpl) również odrzucona")
    void rejectsAnotherApplicationDomainClass() {
        assertThatThrownBy(() ->
                Class.forName("com.contactcenter.domain.customer.CustomerServiceImpl", true, PlatformApiClassLoader.INSTANCE))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("Class.forName na klasę Springa (poza com.contactcenter) jest odrzucona")
    void rejectsSpringFrameworkClass() {
        assertThatThrownBy(() ->
                Class.forName("org.springframework.context.ApplicationContext", true, PlatformApiClassLoader.INSTANCE))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("Klasy JDK bootstrap (java.lang.Object) pozostają dostępne")
    void allowsJdkBootstrapClasses() throws ClassNotFoundException {
        Class<?> clazz = PlatformApiClassLoader.INSTANCE.loadClass("java.lang.Object");

        assertThat(clazz).isEqualTo(Object.class);
    }

    @Test
    @DisplayName("Prefiks podobny, ale nie identyczny (com.contactcenter.pluginsdkx) jest odrzucony")
    void rejectsLookAlikePackagePrefix() {
        assertThatThrownBy(() ->
                Class.forName("com.contactcenter.pluginsdkx.Fake", true, PlatformApiClassLoader.INSTANCE))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("INSTANCE jest singletonem")
    void instanceIsSingleton() {
        assertThat(PlatformApiClassLoader.INSTANCE).isSameAs(PlatformApiClassLoader.INSTANCE);
    }
}
