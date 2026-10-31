import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpContext } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import {
  OAuthInitiateResponse,
  SocialIntegration,
  SocialPlatform,
  WhatsAppConnectRequest,
} from '../models/social-integration.model';
import { SKIP_ERROR_TOAST } from '../../../core/interceptors/error-handler.interceptor';

interface GetIntegrationsResponse {
  integrations: SocialIntegration[];
}

@Injectable({ providedIn: 'root' })
export class SocialIntegrationService {
  private readonly http = inject(HttpClient);

  getIntegrations(): Observable<SocialIntegration[]> {
    return this.http
      .get<GetIntegrationsResponse>('/api/integrations')
      .pipe(map((res) => res.integrations));
  }

  // The component shows its own, more specific error toast for these three calls
  // (see social-integrations.component.ts) — SKIP_ERROR_TOAST prevents the global
  // errorHandlerInterceptor from also showing a generic one on failure.
  initiateOAuth(platform: SocialPlatform): Observable<OAuthInitiateResponse> {
    return this.http.post<OAuthInitiateResponse>(
      `/api/integrations/${platform}/initiate`,
      {},
      { context: new HttpContext().set(SKIP_ERROR_TOAST, true) },
    );
  }

  deleteIntegration(integrationId: string): Observable<void> {
    return this.http.delete<void>(`/api/integrations/${integrationId}`, {
      context: new HttpContext().set(SKIP_ERROR_TOAST, true),
    });
  }

  connectWhatsApp(request: WhatsAppConnectRequest): Observable<SocialIntegration> {
    return this.http.post<SocialIntegration>('/api/integrations/WHATSAPP/connect', request, {
      context: new HttpContext().set(SKIP_ERROR_TOAST, true),
    });
  }
}
