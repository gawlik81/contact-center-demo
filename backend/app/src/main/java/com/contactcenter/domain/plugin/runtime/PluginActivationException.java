package com.contactcenter.domain.plugin.runtime;

/**
 * Wyjątek rzucany gdy aktywacja instalacji pluginu (pobranie JAR-a, classloading,
 * instancjonowanie {@code entryPointClass}, lub {@code onActivate}) nie powiodła się.
 *
 * <p>Mapowany przez {@code GlobalExceptionHandler} przez generyczny fallback (HTTP 500) —
 * nie jest to błąd walidacji danych wejściowych (te są łapane wcześniej, w BE-098/BE-100),
 * ale błąd środowiska wykonawczego (object storage niedostępny, JAR uszkodzony pomiędzy
 * walidacją a aktywacją, plugin rzuca z {@code onActivate}, timeout).
 */
public class PluginActivationException extends RuntimeException {

    public PluginActivationException(String message) {
        super(message);
    }

    public PluginActivationException(String message, Throwable cause) {
        super(message, cause);
    }
}
