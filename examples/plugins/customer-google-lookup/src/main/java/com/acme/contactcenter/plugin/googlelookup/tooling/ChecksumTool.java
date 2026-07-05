package com.acme.contactcenter.plugin.googlelookup.tooling;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Replikuje DOKŁADNIE algorytm checksumu wymagany przez
 * {@code manifest.checksumSha256} (weryfikowany po stronie hosta przez
 * {@code PluginValidationServiceImpl#sha256OfEntriesExcludingManifest}):
 * SHA-256 z konkatenacji (nazwa wpisu UTF-8 + zawartość wpisu) dla wszystkich wpisów JAR-a,
 * sortowanych alfabetycznie po nazwie, Z WYŁĄCZENIEM {@code META-INF/plugin-manifest.json}.
 *
 * <p>Dzięki temu, że wpis manifestu jest wyłączony z hashowania, wartość {@code checksumSha256}
 * NIE wpływa na sam siebie — można zbudować JAR z placeholderem w manifeście, policzyć
 * checksum tym narzędziem, zaktualizować plik manifestu i przebudować bez zmiany checksumu
 * (pod warunkiem, że kompilacja jest deterministyczna — co dla niezmienionego kodu źródłowego
 * jest prawdą). Pełna procedura: README.md tego projektu, sekcja "Budowanie".
 *
 * <p>Użycie po {@code mvn package}:
 * <pre>{@code
 * java -cp target/classes com.acme.contactcenter.plugin.googlelookup.tooling.ChecksumTool \
 *     target/customer-google-lookup-plugin-1.0.0.jar
 * }</pre>
 */
public final class ChecksumTool {

    private static final String MANIFEST_ENTRY = "META-INF/plugin-manifest.json";

    private ChecksumTool() {
    }

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        if (args.length != 1) {
            System.err.println("Użycie: ChecksumTool <ścieżka-do-jara>");
            System.exit(1);
            return;
        }
        System.out.println(compute(args[0]));
    }

    static String compute(String jarPath) throws IOException, NoSuchAlgorithmException {
        try (ZipFile zipFile = new ZipFile(jarPath)) {
            List<String> entryNames = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.isDirectory() && !MANIFEST_ENTRY.equals(entry.getName())) {
                    entryNames.add(entry.getName());
                }
            }
            entryNames.sort(String::compareTo);

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String entryName : entryNames) {
                digest.update(entryName.getBytes(StandardCharsets.UTF_8));
                try (InputStream in = zipFile.getInputStream(zipFile.getEntry(entryName))) {
                    digest.update(in.readAllBytes());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
