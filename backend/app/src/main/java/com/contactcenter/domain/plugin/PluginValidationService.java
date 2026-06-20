package com.contactcenter.domain.plugin;

import com.contactcenter.domain.plugin.dto.ValidationResult;

import java.util.UUID;

/**
 * Serwis walidacji JAR-a pluginu, wykonywany w pełni <strong>przed</strong> dotknięciem
 * jakiejkolwiek klasy z JAR-a (ARCHITECTURE.md §11.4) — gate bezpieczeństwa dla systemu
 * pluginów per tenant (EPIC-28).
 *
 * <p>Pipeline walidacji (w tej kolejności, każdy krok może zakończyć pipeline z
 * {@code REJECTED}):
 * <ol>
 *   <li>Guard rozmiaru (&le;50MB) i MIME (magic bytes {@code PK\x03\x04})</li>
 *   <li>SHA-256 uploadowanych bajtów vs {@code manifest.checksumSha256}</li>
 *   <li>JSON Schema manifestu ({@code META-INF/plugin-manifest.json})</li>
 *   <li>Statyczny skan bytecode (ASM, bez ładowania klas) — blacklist API, weryfikacja
 *       {@code entryPointClass} implementuje {@code PluginEntryPoint}, podzbiór enumów
 *       platformy dla {@code extensionPoints}/{@code permissions}</li>
 *   <li>Hook na weryfikację podpisu kryptograficznego — no-op w tej implementacji
 *       (OQ-28-1, świadomie odłożone)</li>
 * </ol>
 *
 * <p><strong>Ten serwis tylko waliduje.</strong> Zapis JAR-a do object storage i wstawienie
 * wiersza {@code plugin_version} jest zakresem BE-099 (kolejny ticket), nie tego serwisu.
 */
public interface PluginValidationService {

    /**
     * Wykonuje pełny pipeline walidacji JAR-a pluginu.
     *
     * @param jarBytes        surowe bajty wgranego pliku JAR
     * @param uploadedByUserId identyfikator użytkownika wykonującego upload (do audytu/logów,
     *                          nie wpływa na wynik walidacji)
     * @return {@link ValidationResult} ze statusem {@code VALIDATED} (sukces) lub
     *         {@code REJECTED} (porażka, z opisowymi błędami w {@code validationErrors})
     */
    ValidationResult validate(byte[] jarBytes, UUID uploadedByUserId);
}
