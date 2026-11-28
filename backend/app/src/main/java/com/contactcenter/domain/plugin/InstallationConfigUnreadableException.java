package com.contactcenter.domain.plugin;

/**
 * Thrown when all (or most) enabled installations could not be read because their {@code installation_config} does
 * not decrypt/parse (BE-245 W3). That is a SYSTEMIC failure (key rotation / wrong key), not one corrupt row, so the
 * caller must fail loudly instead of treating an empty list as "no plugins enabled".
 */
public class InstallationConfigUnreadableException extends IllegalStateException {

    private final int total;
    private final int skippedDecrypt;
    private final int skippedFormat;

    public InstallationConfigUnreadableException(int total, int skippedDecrypt, int skippedFormat) {
        super("Nie można odczytać konfiguracji " + (skippedDecrypt + skippedFormat) + " z " + total
                + " włączonych instalacji wtyczek (decrypt=" + skippedDecrypt + ", format=" + skippedFormat
                + ") - sprawdź klucz szyfrowania app.encryption.secret");
        this.total = total;
        this.skippedDecrypt = skippedDecrypt;
        this.skippedFormat = skippedFormat;
    }

    public int total() { return total; }
    public int skippedDecrypt() { return skippedDecrypt; }
    public int skippedFormat() { return skippedFormat; }
}
