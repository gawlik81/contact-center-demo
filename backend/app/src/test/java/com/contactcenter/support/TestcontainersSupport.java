package com.contactcenter.support;

/**
 * Wspólne ustawienia Testcontainers dla testów integracyjnych.
 */
public final class TestcontainersSupport {

    private TestcontainersSupport() {
    }

    /**
     * Testcontainers 1.20.4 (docker-java 3.4.0) negocjuje domyślnie API Dockera 1.32, które najnowsze
     * silniki odrzucają (400 Bad Request: „client version 1.32 is too old"). Wymuszamy 1.44 (najwyższa
     * wersja znana tej wersji docker-java) TYLKO gdy nic innego nie jest skonfigurowane (env
     * {@code API_VERSION}, property {@code api.version}, {@code ~/.docker-java.properties}) — żeby nie
     * nadpisywać świadomej konfiguracji CI. Wołać PRZED utworzeniem pierwszego kontenera.
     */
    public static void ensureDockerApiVersion() {
        if (System.getProperty("api.version") == null && System.getenv("API_VERSION") == null) {
            System.setProperty("api.version", "1.44");
        }
    }
}
