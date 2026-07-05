package com.contactcenter.domain.plugin.dto;

/**
 * Akcja manualna agenta deklarowana przez manifest pluginu, wzbogacająca
 * {@link TenantPluginInstallationDto} (FE-097, EPIC-28).
 *
 * <p>Publiczny odpowiednik package-private {@code PluginManifest.ManualAction}
 * (pakiet {@code domain.plugin}) — niewidoczny z {@code domain.plugin.dto}, stąd
 * osobny, prosty rekord wyłącznie do ekspozycji przez API. Pola odpowiadają 1:1
 * modelowi TypeScript {@code ManualActionDef} (TASKS-FRONTEND.md, FE-097).
 *
 * @param actionId   unikalny identyfikator akcji w ramach pluginu
 * @param label      etykieta wyświetlana agentowi
 * @param mountPoint miejsce montowania w UI, np. {@code "AGENT_DESKTOP_TOOLBAR"}
 */
public record ManualActionDto(String actionId, String label, String mountPoint) {
}
