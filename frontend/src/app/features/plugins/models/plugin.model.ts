/**
 * Status walidacji wersji pluginu (katalog globalny, BE-098/BE-099).
 */
export type PluginVersionStatus =
  | 'UPLOADED'
  | 'VALIDATED'
  | 'PENDING_REVIEW'
  | 'REJECTED'
  | 'REVOKED';

/**
 * Status zdrowia instalacji pluginu u tenanta (circuit breaker, ARCHITECTURE.md §11.7).
 */
export type PluginHealthStatus = 'HEALTHY' | 'DEGRADED' | 'DISABLED_BY_ADMIN';

/**
 * Punkty rozszerzeń, w które plugin może się podczepić (manifest pluginu, ARCHITECTURE.md §11).
 */
export type ExtensionPoint =
  | 'PRE_CONTACT_CONNECT'
  | 'POST_CONTACT_END'
  | 'CUSTOMER_SYNC'
  | 'DISPOSITION_SET'
  | 'MANUAL_ACTION';

/**
 * Jedna wersja pluginu w globalnym katalogu (odpowiednik backendowego `PluginVersionDto`).
 */
export interface PluginVersionDto {
  id: string;
  pluginId: string;
  pluginKey: string;
  displayName: string;
  vendor: string;
  version: string;
  sdkVersion: string;
  status: PluginVersionStatus;
  validationErrors: string[] | null;
  /**
   * Uprawnienia deklarowane w manifeście pluginu (np. "customer:read"), do prezentacji
   * w dialogu instalacji jako checkboxy `grantedPermissions` (FE-098). Dodane do backendowego
   * `PluginVersionDto` po napisaniu FE-097 — patrz `PluginVersionDto.java`.
   */
  permissions: string[];
  uploadedByUserId: string;
  uploadedAt: string;
}

/**
 * Akcja manualna agenta deklarowana w manifeście pluginu (odpowiednik `ManualActionDto`).
 */
export interface ManualActionDef {
  actionId: string;
  label: string;
  mountPoint: string;
}

/**
 * Panel UI agenta deklarowany w manifeście pluginu (odpowiednik `UiPanelDto`).
 *
 * Świadomie bez pola `sandbox` — atrybut sandboxu iframe jest ustalany przez hosta
 * (FE-099 `cc-plugin-panel-host`), nie konsumowany z API.
 */
export interface UiPanelDef {
  panelId: string;
  mountPoint: string;
  url: string;
}

/**
 * Instalacja pluginu u tenanta (odpowiednik backendowego `TenantPluginInstallationDto`).
 */
export interface TenantPluginInstallationDto {
  id: string;
  tenantId: string;
  pluginVersionId: string;
  pluginKey: string;
  displayName: string;
  version: string;
  enabled: boolean;
  grantedPermissions: string[];
  healthStatus: PluginHealthStatus;
  consecutiveFailureCount: number;
  manualActions: ManualActionDef[];
  uiPanels: UiPanelDef[];
  installedByUserId: string;
  installedAt: string;
  updatedAt: string;
}

/**
 * Żądanie instalacji wersji pluginu u tenanta.
 */
export interface InstallPluginRequest {
  pluginVersionId: string;
  grantedPermissions: string[];
}
