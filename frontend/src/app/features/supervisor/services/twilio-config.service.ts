import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { environment } from '../../../../environments/environment';

// ── Legacy DTOs (used by twilio-settings.component.ts – do not remove) ────────

export interface TwilioConfigRequest {
  twilioPhoneNumber: string | null;
  twilioStatusCallbackUrl: string | null;
}

export interface TenantConfig {
  id: string;
  name: string;
  config: {
    twilio_phone_number?: string;
    twilio_status_callback_url?: string;
    [key: string]: unknown;
  };
}

// ── New supervisor-scoped DTOs (FE-066) ────────────────────────────────────────

export interface TenantTwilioConfigResponse {
  configId: string;
  tenantId: string;
  accountSid: string;
  authToken: string | null;
  apiKeySid: string | null;
  apiKeySecret: string | null;
  twimlAppSid: string | null;
  phoneNumber: string | null;
  statusCallbackUrl: string | null;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface TenantTwilioConfigRequest {
  accountSid: string;
  authToken: string;
  apiKeySid?: string | null;
  apiKeySecret?: string | null;
  twimlAppSid?: string | null;
  phoneNumber?: string | null;
  statusCallbackUrl?: string | null;
}

export interface TwilioConnectionTestResult {
  success: boolean;
  message: string;
  testedAt: string;
}

@Injectable({ providedIn: 'root' })
export class TwilioConfigService {
  private readonly http = inject(HttpClient);

  // ── Legacy base URL (used by twilio-settings.component.ts) ──────────────────
  private readonly baseUrl = `${environment.apiUrl}/tenants`;

  // ── New supervisor-scoped base URL ───────────────────────────────────────────
  private readonly supervisorBaseUrl = `${environment.apiUrl}/supervisor/twilio-config`;

  // ── Legacy methods (kept for twilio-settings.component.ts) ──────────────────

  getTenantConfig(tenantId: string): Observable<TenantConfig> {
    return this.http.get<TenantConfig>(`${this.baseUrl}/${tenantId}/config`);
  }

  updateTwilioConfig(tenantId: string, request: TwilioConfigRequest): Observable<TenantConfig> {
    return this.http.patch<TenantConfig>(`${this.baseUrl}/${tenantId}/config`, request);
  }

  getTelephonyFeatures(): Observable<{ perTenantCallbackUrlEnabled: boolean }> {
    return this.http.get<{ perTenantCallbackUrlEnabled: boolean }>(
      `${environment.apiUrl}/telephony/features`,
    );
  }

  // ── New supervisor-scoped methods (FE-066) ───────────────────────────────────

  getConfig(): Observable<TenantTwilioConfigResponse | null> {
    return this.http
      .get<TenantTwilioConfigResponse>(this.supervisorBaseUrl, { observe: 'response' })
      .pipe(map((r) => (r.status === 204 ? null : r.body)));
  }

  saveConfig(data: TenantTwilioConfigRequest): Observable<TenantTwilioConfigResponse> {
    return this.http.put<TenantTwilioConfigResponse>(this.supervisorBaseUrl, data);
  }

  deleteConfig(): Observable<void> {
    return this.http.delete<void>(this.supervisorBaseUrl);
  }

  testConnection(): Observable<TwilioConnectionTestResult> {
    return this.http.post<TwilioConnectionTestResult>(`${this.supervisorBaseUrl}/test`, {});
  }
}
