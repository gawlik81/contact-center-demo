package com.contactcenter.domain.plugin.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of plugin JARs and the cheap "did the local copy change" pre-check (BE-246).
 *
 * <p>Two different questions, two different costs:
 * <ul>
 *   <li><em>Does the JAR we are about to load match the catalog?</em> - hashed ONCE, from the bytes just downloaded
 *       ({@link #sha256Hex(byte[])}), compared with {@code plugin_version.checksum_sha256}. A mismatch refuses the
 *       load (the object storage does not revalidate on read, and an in-place overwrite changes the row and the object
 *       key in separate steps).</li>
 *   <li><em>Was the JAR replaced afterwards?</em> - reconciliation compares the checksum remembered in the handle with
 *       the one in the catalog (a string comparison, no I/O), and only {@link #localCopyChanged} touches the disk: a
 *       {@code stat} (size + mtime) every pass, a full hash ONLY when the stat differs from the one recorded at load.</li>
 * </ul>
 */
final class PluginJarIntegrity {

    private PluginJarIntegrity() {
    }

    static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    static String sha256Hex(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** {@code size:mtimeMillis} of the file, or {@code null} when it cannot be read (deleted). */
    static String statOf(Path file) {
        try {
            return Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * {@code true} when the local copy of the JAR no longer matches the checksum the handle was loaded with: the file
     * is gone, or its stat differs from {@code acceptedStat} AND its content hashes differently. Handles without a
     * recorded checksum (test handles) are never reported.
     *
     * @param acceptedStat stat already proven to hold the expected content (initially the stat recorded at load)
     */
    static boolean localCopyChanged(PluginInstanceHandle handle, String acceptedStat) {
        if (handle.jarChecksumSha256() == null || handle.localJarPath() == null) {
            return false;
        }
        String stat = statOf(handle.localJarPath());
        if (stat == null) {
            return true;
        }
        if (stat.equals(acceptedStat)) {
            return false;
        }
        try {
            return !handle.jarChecksumSha256().equalsIgnoreCase(sha256Hex(handle.localJarPath()));
        } catch (IOException e) {
            return true;
        }
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 niedostępne w JVM", e);
        }
    }
}
