import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { environment } from '../../../../environments/environment';

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

@Injectable({ providedIn: 'root' })
export class TwilioConfigService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/tenants`;

  getTenantConfig(tenantId: string): Observable<TenantConfig> {
    return this.http.get<TenantConfig>(`${this.baseUrl}/${tenantId}/config`);
  }

  updateTwilioConfig(tenantId: string, request: TwilioConfigRequest): Observable<TenantConfig> {
    return this.http.patch<TenantConfig>(`${this.baseUrl}/${tenantId}/config`, request);
  }
}
