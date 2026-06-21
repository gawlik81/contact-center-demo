package com.contactcenter.domain.plugin.runtime;

import java.util.concurrent.Callable;

/**
 * Granica Thread-Context ClassLoader (TCCL) wokół wywołania kodu pluginu (ARCHITECTURE.md §11.3,
 * RT-10) — analogiczna do wzorca {@code TenantContext.snapshot()}/{@code restore()}/{@code clear()}
 * już wymaganego przez CLAUDE.md, tylko dla TCCL.
 *
 * <p><strong>Why:</strong> wątki workerów (np. {@code lifecycleExecutor} w
 * {@link PluginRuntimeManagerImpl}) dziedziczą domyślnie TCCL wątku, który je utworzył — czyli
 * pełny classloader aplikacji Springa. Jeśli kod pluginu (świadomie, lub przypadkowo przez
 * bibliotekę trzecią — {@code ServiceLoader.load}, JAXB, niektóre biblioteki JSON/XML) zawoła
 * {@code Thread.currentThread().getContextClassLoader()} bez tej granicy ustawionej, dostaje
 * classloader aplikacji i może przez {@code Class.forName(name, true, tccl)} dotrzeć do
 * dowolnej klasy hosta (np. {@code domain.tenant.TenantServiceImpl}) — całkowicie obchodząc
 * filtr {@link PlatformApiClassLoader#loadClass}, który chroni TYLKO ścieżkę explicit
 * {@code PluginClassLoader} → {@code PlatformApiClassLoader}.
 *
 * <p><strong>Reużycie:</strong> każde miejsce w tej klasie/pakiecie, które wywołuje kod pluginu
 * (np. {@code entryPoint.onActivate}/{@code onDeactivate} w {@link PluginRuntimeManagerImpl}, a
 * w przyszłości {@code PluginInvocationExecutor}/BE-102 dla wszystkich hooków rozszerzeń) MUSI
 * opakować to wywołanie przez {@link #runWithPluginClassLoader(ClassLoader, Callable)} —
 * nigdy wołać kod pluginu bezpośrednio na wątku roboczym bez tej granicy.
 */
final class PluginExecutionContext {

    private PluginExecutionContext() {
    }

    /**
     * Wykonuje {@code action} z TCCL wątku wywołującego ustawionym na {@code pluginClassLoader}
     * na czas trwania akcji, przywracając poprzedni TCCL w {@code finally} niezależnie od wyniku
     * (sukces, wyjątek czy timeout zewnętrzny obsłużony przez wołającego).
     *
     * @param pluginClassLoader classloader dedykowany instalacji pluginu, którego kod jest
     *                          wywoływany (nigdy classloader aplikacji)
     * @param action            kod pluginu do wywołania (np. {@code entryPoint::onActivate})
     * @param <T>                typ wyniku akcji
     * @return wynik {@code action.call()}
     * @throws Exception wszystko, co rzuci {@code action} — przekazywane bez zmian wołającemu
     */
    static <T> T runWithPluginClassLoader(ClassLoader pluginClassLoader, Callable<T> action) throws Exception {
        Thread currentThread = Thread.currentThread();
        ClassLoader previousClassLoader = currentThread.getContextClassLoader();
        currentThread.setContextClassLoader(pluginClassLoader);
        try {
            return action.call();
        } finally {
            currentThread.setContextClassLoader(previousClassLoader);
        }
    }
}
