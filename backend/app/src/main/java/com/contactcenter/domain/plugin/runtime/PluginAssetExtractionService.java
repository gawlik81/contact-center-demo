package com.contactcenter.domain.plugin.runtime;

import com.contactcenter.domain.plugin.PluginVersion;

import java.nio.file.Path;

/**
 * Rozpakowuje katalog {@code plugin-ui/} z JAR-a pluginu na dysk lokalny węzła (EPIC-28, BE-107,
 * ARCHITECTURE.md §11.10).
 *
 * <p>Wywoływana wyłącznie przez {@link PluginRuntimeManagerImpl#load} — analogicznie do
 * {@code downloadJarToLocalCache}, ekstrakcja zasobów UI dzieje się tylko raz, przy aktywacji
 * instalacji, i jest czyszczona przy {@code unload} (rekursywne usunięcie katalogu).
 *
 * <p><strong>No-op, nie błąd, gdy JAR nie ma katalogu {@code plugin-ui/}:</strong> manifest
 * {@code uiPanels} jest opcjonalny (może być pustą listą) — plugin może deklarować wyłącznie
 * extension points backendowe, bez żadnego UI. W takim wypadku ta metoda nie tworzy żadnych
 * plików i {@code PluginInstanceHandle#uiAssetsDir} pozostaje {@code Optional.empty()}.
 */
public interface PluginAssetExtractionService {

    /**
     * Rozpakowuje wpisy JAR-a zaczynające się od {@code plugin-ui/} do {@code destinationDir}.
     *
     * @param pluginVersion   wersja pluginu, z której JAR-a (object storage,
     *                        {@link PluginVersion#getJarObjectKey()}) ekstrahujemy zasoby
     * @param destinationDir  katalog docelowy na dysku węzła (musi już istnieć lub być
     *                        tworzony przez implementację — patrz Javadoc implementacji)
     * @return {@code true} jeśli JAR zawierał katalog {@code plugin-ui/} i co najmniej jeden
     *         plik został wyekstrahowany; {@code false} jeśli katalog nie istniał w JAR-ze
     *         (no-op, nie błąd)
     */
    boolean extract(PluginVersion pluginVersion, Path destinationDir);
}
