package com.contactcenter.domain.plugin.runtime;

import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.io.IOException;

/**
 * Wąski, "platform-api" {@code ClassLoader} eksponujący <strong>wyłącznie</strong> pakiet
 * {@code com.contactcenter.pluginsdk} (i jego podpakiety, np. {@code com.contactcenter.pluginsdk.model}) —
 * jądro modelu izolacji z ARCHITECTURE.md §11.3.
 *
 * <p><strong>Jeden singleton dla całego JVM</strong> (pole {@link #INSTANCE}), nie per-instalacja
 * pluginu — wszystkie {@link PluginClassLoader} (każdy reprezentujący jedną instalację
 * {@code (tenant_id, plugin_key)}) dzielą tego samego rodzica. Współdzielenie tego jednego
 * classloadera jest bezpieczne, bo {@code plugin-sdk} (BE-097) zawiera tylko interfejsy i
 * niemutowalne rekordy — zero stanu statycznego, zero implementacji.
 *
 * <p><strong>Mechanizm ograniczenia do {@code com.contactcenter.pluginsdk.*}:</strong>
 * <ol>
 *   <li>Parentem tego classloadera jest classloader, który faktycznie załadował tę klasę
 *       (classloader aplikacji/Spring Boot fat-jar) — jedyne miejsce na classpath gdzie
 *       {@code plugin-sdk} fizycznie istnieje.</li>
 *   <li>{@link #loadClass(String, boolean)} jest <strong>nadpisane</strong> tak, by
 *       jakikolwiek fully-qualified name NIE zaczynający się od {@code com.contactcenter.pluginsdk.}
 *       (i nie będący klasą bootstrap JDK, patrz niżej) skutkował natychmiastowym
 *       {@link ClassNotFoundException} — <strong>nigdy</strong> nie pytamy parenta o nic poza
 *       tym jednym, wąskim prefiksem.</li>
 *   <li>Dla nazw w dozwolonym prefiksie, delegujemy bezpośrednio do
 *       {@code super.loadClass(name, resolve)} (czyli ostatecznie do classloadera aplikacji) —
 *       <strong>nie</strong> redefiniujemy bajtów przez {@code defineClass}. To jest
 *       celowe: gdybyśmy zdefiniowali np. {@code com.contactcenter.pluginsdk.PluginEntryPoint}
 *       jako NOWY obiekt {@code Class} w tym classloaderze, to byłaby ona inną tożsamością
 *       typu niż {@code PluginEntryPoint} widziany przez kod aplikacji (np.
 *       {@code PluginRuntimeManagerImpl}), i każdy {@code instanceof}/cast pluginu na ten
 *       interfejs zawiodłyby z {@code ClassCastException} ("loader constraint violation") —
 *       klasyczny problem dwóch definicji tej samej nazwy klasy w różnych classloaderach.
 *       Delegacja do parenta dla TYLKO tego jednego, wąskiego prefiksu gwarantuje identyczną
 *       tożsamość typu po obu stronach granicy, zachowując jednocześnie pełną izolację (parent
 *       nigdy nie jest pytany o nic poza {@code com.contactcenter.pluginsdk.*} — żądanie innej
 *       nazwy nie dociera do {@code super.loadClass} wcale, więc nie ma znaczenia, że parent
 *       "widziałby" cały classpath aplikacji, gdyby został o to poproszony).</li>
 * </ol>
 *
 * <p><strong>Co to NIE robi:</strong> nie wykonuje żadnej weryfikacji bytecode'u, nie analizuje
 * treści klas SDK — ufa, że {@code plugin-sdk} jest zbudowany bez zależności (zweryfikowane w
 * BE-097: {@code mvn dependency:tree -pl plugin-sdk} zwraca puste drzewo). Bezpieczeństwo tego
 * classloadera polega wyłącznie na filtrze nazw pakietów w {@link #loadClass}, nie na analizie
 * treści.
 */
public final class PlatformApiClassLoader extends ClassLoader {

    /**
     * Prefiks pakietu, do którego ograniczony jest ten classloader. Każda klasa, której
     * fully-qualified name nie zaczyna się od tego prefiksu, jest dla tego classloadera
     * niewidoczna — niezależnie od tego, czy istnieje na classpath aplikacji.
     */
    static final String ALLOWED_PACKAGE_PREFIX = "com.contactcenter.pluginsdk";

    /**
     * Jedyna instancja dla całego JVM. Wszystkie {@link PluginClassLoader} używają tego samego
     * rodzica — nie tworzymy nowej instancji per instalacja/tenant (ARCHITECTURE.md §11.3:
     * "jeden classloader dla całego JVM").
     */
    public static final PlatformApiClassLoader INSTANCE = new PlatformApiClassLoader();

    private PlatformApiClassLoader() {
        // Parent = classloader, który załadował tę klasę (classloader aplikacji) — jedyne
        // miejsce gdzie plugin-sdk fizycznie istnieje na classpath. Dostęp do niego jest
        // ograniczony wyłącznie przez filtr nazw w loadClass() poniżej.
        super(PlatformApiClassLoader.class.getClassLoader());
    }

    /**
     * Nadpisane tak, by nazwy poza {@link #ALLOWED_PACKAGE_PREFIX} (i poza bootstrap JDK) nigdy
     * nie docierały do {@code super.loadClass(...)} — w szczególności nigdy nie udostępniamy
     * pluginowi żadnej klasy aplikacji/Springa przez ten classloader.
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> alreadyLoaded = findLoadedClass(name);
            if (alreadyLoaded != null) {
                return resolveIfNeeded(alreadyLoaded, resolve);
            }

            if (isJdkBootstrapClass(name)) {
                // java.lang.*, java.util.* itd. muszą pozostać dostępne — SDK i JVM ich
                // wymagają (np. java.lang.Object jest superklasą każdej klasy). Delegujemy
                // wyłącznie do bootstrap classloadera (null), NIE do classloadera aplikacji.
                Class<?> bootstrapClass = findBootstrapClassOrNull(name);
                if (bootstrapClass != null) {
                    return resolveIfNeeded(bootstrapClass, resolve);
                }
                throw new ClassNotFoundException(name);
            }

            if (!isInAllowedPackage(name)) {
                throw new ClassNotFoundException(
                        "PlatformApiClassLoader eksponuje wyłącznie pakiet " + ALLOWED_PACKAGE_PREFIX
                                + " — odmowa dla: " + name);
            }

            // Delegacja do classloadera aplikacji (parent) — wyłącznie dla nazw już
            // zweryfikowanych jako com.contactcenter.pluginsdk.*. Zachowuje tożsamość typu
            // identyczną z tą, którą widzi kod aplikacji (PluginRuntimeManagerImpl i inni),
            // co jest niezbędne dla poprawnych instanceof/cast na PluginEntryPoint/PluginContext.
            Class<?> fromApplicationClassLoader = super.loadClass(name, resolve);
            return resolveIfNeeded(fromApplicationClassLoader, resolve);
        }
    }

    @Override
    public URL getResource(String resourceName) {
        // Analogiczne ograniczenie dla zasobów (nie tylko klas) — plugin nie powinien móc
        // odczytać arbitralnego zasobu z classpath aplikacji przez ten classloader.
        if (!isResourceInAllowedPackage(resourceName)) {
            return null;
        }
        ClassLoader parent = getParent();
        return parent != null ? parent.getResource(resourceName) : null;
    }

    @Override
    public Enumeration<URL> getResources(String resourceName) {
        if (!isResourceInAllowedPackage(resourceName)) {
            return Collections.emptyEnumeration();
        }
        try {
            ClassLoader parent = getParent();
            return parent != null ? parent.getResources(resourceName) : Collections.emptyEnumeration();
        } catch (IOException e) {
            return Collections.emptyEnumeration();
        }
    }

    // =========================================================================
    // Metody pomocnicze
    // =========================================================================

    private static boolean isInAllowedPackage(String fullyQualifiedClassName) {
        return fullyQualifiedClassName.startsWith(ALLOWED_PACKAGE_PREFIX + ".")
                || fullyQualifiedClassName.equals(ALLOWED_PACKAGE_PREFIX);
    }

    private static boolean isResourceInAllowedPackage(String resourceName) {
        String dotted = resourceName.replace('/', '.');
        return dotted.startsWith(ALLOWED_PACKAGE_PREFIX);
    }

    private static boolean isJdkBootstrapClass(String name) {
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.");
    }

    private static Class<?> findBootstrapClassOrNull(String name) {
        try {
            // Class.forName z loader=null rozwiązuje przez bootstrap classloader (natywny w JVM),
            // nigdy przez classloader aplikacji.
            return Class.forName(name, false, null);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Class<?> resolveIfNeeded(Class<?> clazz, boolean resolve) {
        // Parametr "resolve" jest częścią kontraktu ClassLoader#loadClass — JVM już dokonuje
        // niezbędnego linkowania przy pierwszym użyciu klasy; nie jest wymagana dodatkowa
        // jawna akcja tutaj. Zachowane dla zgodności z sygnaturą nadklasy.
        return clazz;
    }
}
