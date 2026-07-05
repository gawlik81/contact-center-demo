package com.contactcenter.domain.plugin.dto;

/**
 * Panel UI agenta deklarowany przez manifest pluginu, wzbogacający
 * {@link TenantPluginInstallationDto} (FE-097, EPIC-28).
 *
 * <p>Publiczny odpowiednik package-private {@code PluginManifest.UiPanel}
 * (pakiet {@code domain.plugin}) — niewidoczny z {@code domain.plugin.dto}, stąd
 * osobny, prosty rekord wyłącznie do ekspozycji przez API. <strong>Świadomie bez
 * pola {@code sandbox}</strong> — model TypeScript {@code UiPanelDef} (FE-097,
 * TASKS-FRONTEND.md linie ~4899-4903) go nie zawiera; atrybut sandboxu iframe jest
 * ustalany przez hosta (FE-099 {@code cc-plugin-panel-host}), nie konsumowany z API.
 *
 * @param panelId    unikalny identyfikator panelu w ramach pluginu
 * @param mountPoint miejsce montowania w UI, np. {@code "AGENT_DESKTOP_SIDE_PANEL"}
 * @param url        adres źródła panelu (np. {@code "classpath:/plugin-ui/index.html"})
 */
public record UiPanelDto(String panelId, String mountPoint, String url) {
}
