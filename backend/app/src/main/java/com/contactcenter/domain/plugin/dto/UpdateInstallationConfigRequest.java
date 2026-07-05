package com.contactcenter.domain.plugin.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Żądanie aktualizacji konfiguracji instalacji pluginu (np. API key zewnętrznego systemu,
 * EPIC-28, plugin przykładowy {@code customer-google-lookup}).
 *
 * <p><strong>Semantyka: REPLACE całej konfiguracji, nie merge.</strong> Każde wywołanie
 * {@code PATCH /api/supervisor/plugins/installations/{id}/config} zastępuje CAŁY dotychczasowy
 * zestaw kluczy nową mapą {@code config} — admin musi wysłać pełny, aktualny zestaw kluczy
 * przy każdym wywołaniu (nie tylko zmieniane pola). Brak wysłanego klucza = usunięcie go
 * z konfiguracji instalacji.
 *
 * <p>Wartości są szyfrowane (AES-256-GCM) przed zapisem do bazy
 * ({@code TenantPluginInstallation.installationConfig}, kolumna {@code jsonb}) — zob.
 * {@link com.contactcenter.domain.plugin.PluginRegistrationService#updateConfig}. Nigdy nie są
 * zwracane przez żadne API (sekrety nie wracają do frontendu po zapisie).
 *
 * @param config mapa klucz→wartość konfiguracji tenanta dla tej instalacji (może być pusta —
 *               oznacza wyczyszczenie całej konfiguracji; nie może być {@code null})
 */
public record UpdateInstallationConfigRequest(
        @NotNull Map<String, String> config
) {
}
