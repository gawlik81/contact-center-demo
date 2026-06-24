import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  InstallPluginRequest,
  PluginVersionDto,
  TenantPluginInstallationDto,
} from '../models/plugin.model';

/**
 * Warstwa danych panelu administracyjnego pluginów per tenant (EPIC-28).
 *
 * Łączy trzy kontrolery backendowe:
 * - `PluginUploadController` (`POST /api/supervisor/plugins`) — upload JAR-a do globalnego
 *   katalogu pluginów.
 * - `PluginAdminController` (`/api/supervisor/plugins/...`) — instalacja/enable/disable/
 *   rollback/uninstall per tenant (rola SUPERVISOR/ADMIN).
 * - `PluginAgentController` (`GET /api/agent/plugins`) — lekki, tylko-odczyt endpoint dla
 *   roli AGENT (FE-100), zwraca tylko instalacje `enabled=true` (filtr po stronie backendu).
 *
 * Czysta warstwa danych (FE-097) — bez logiki UI/komponentów, te przyjdą w FE-098/FE-099/FE-100.
 */
@Injectable({ providedIn: 'root' })
export class PluginAdminService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/supervisor/plugins';
  private readonly agentBase = '/api/agent/plugins';

  /**
   * Wgrywa JAR pluginu do globalnego katalogu (multipart/form-data, pole "file").
   */
  uploadJar(file: File): Observable<PluginVersionDto> {
    const formData = new FormData();
    formData.append('file', file);
    return this.http.post<PluginVersionDto>(this.base, formData);
  }

  /**
   * Listuje wszystkie instalacje pluginów tenanta (włącznie z disabled).
   */
  listInstallations(): Observable<TenantPluginInstallationDto[]> {
    return this.http.get<TenantPluginInstallationDto[]>(this.base);
  }

  /**
   * Listuje wszystkie wersje pluginów w globalnym katalogu, niezależnie od tenanta/instalacji
   * (BE-110). Przeglądarka katalogu — pozwala zainstalować wersję, której `pluginVersionId`
   * nie pochodzi ze świeżej odpowiedzi `uploadJar` (np. gdy upload przeszedł walidację, ale
   * zapis do bazy zawiódł, bo wersja już istniała w katalogu z wcześniejszej próby).
   */
  listCatalog(): Observable<PluginVersionDto[]> {
    return this.http.get<PluginVersionDto[]>(`${this.base}/catalog`);
  }

  /**
   * Instaluje wersję pluginu dla tenanta (enabled=false po instalacji).
   */
  install(req: InstallPluginRequest): Observable<TenantPluginInstallationDto> {
    return this.http.post<TenantPluginInstallationDto>(
      `${this.base}/${req.pluginVersionId}/install`,
      { grantedPermissions: req.grantedPermissions },
    );
  }

  /**
   * Włącza instalację pluginu (DB flag + idempotentny load runtime).
   */
  enable(installationId: string): Observable<void> {
    return this.http.post<void>(`${this.base}/installations/${installationId}/enable`, {});
  }

  /**
   * Wyłącza instalację pluginu (natychmiastowy unload runtime, potem DB flag).
   */
  disable(installationId: string): Observable<void> {
    return this.http.post<void>(`${this.base}/installations/${installationId}/disable`, {});
  }

  /**
   * Rollback do starszej wersji: włącza targetId, wyłącza installationId.
   */
  rollback(installationId: string, targetId: string): Observable<TenantPluginInstallationDto> {
    return this.http.post<TenantPluginInstallationDto>(
      `${this.base}/installations/${installationId}/rollback/${targetId}`,
      {},
    );
  }

  /**
   * Odinstalowuje (usuwa) instalację pluginu (unload runtime best-effort + DB delete).
   */
  uninstall(installationId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/installations/${installationId}`);
  }

  /**
   * Zastępuje (REPLACE, nie merge) cały zestaw konfiguracji instalacji pluginu. Wartości są
   * szyfrowane AES-256-GCM po stronie backendu i nigdy nie wracają w odpowiedzi API — nie ma
   * odpowiadającej metody GET, formularz wywołujący to zawsze musi startować pusty.
   */
  updateConfig(installationId: string, config: Record<string, string>): Observable<void> {
    return this.http.patch<void>(`${this.base}/installations/${installationId}/config`, { config });
  }

  /**
   * Listuje instalacje pluginów widoczne dla roli AGENT (`PluginAgentController`, FE-100).
   * Backend filtruje tylko po `enabled=true` — NIE po `healthStatus`, więc wołający musi
   * dodatkowo odfiltrować `healthStatus === 'DISABLED_BY_ADMIN'` po stronie FE.
   */
  listAgentInstallations(): Observable<TenantPluginInstallationDto[]> {
    return this.http.get<TenantPluginInstallationDto[]>(this.agentBase);
  }
}
