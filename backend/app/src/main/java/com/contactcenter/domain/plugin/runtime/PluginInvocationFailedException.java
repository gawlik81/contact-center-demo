package com.contactcenter.domain.plugin.runtime;

/**
 * Wrapper rzucany na wątku roboczym {@code pluginInvocationExecutor} gdy wywołanie
 * {@code PluginEntryPoint} zawiedzie z jakiegokolwiek powodu — w tym {@code Throwable}/
 * {@code Error}, nie tylko {@code Exception} (ARCHITECTURE.md §11.7 "Exception containment").
 *
 * <p>{@code Future<R>.get()} propaguje przyczynę owinięta w {@code ExecutionException} do
 * wątku wywołującego ({@link ExtensionPointPublisherImpl#invokeBlocking}), który ją loguje jako
 * {@code FAILED} i zwraca wynik domyślny SDK — w żadnym wypadku nie propaguje wyjątku/błędu
 * dalej do kodu agenta/desktopu.
 */
final class PluginInvocationFailedException extends RuntimeException {

    PluginInvocationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
