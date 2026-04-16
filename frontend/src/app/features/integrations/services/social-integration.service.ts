import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import {
  OAuthInitiateResponse,
  SocialIntegration,
  SocialPlatform,
} from '../models/social-integration.model';

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

  initiateOAuth(platform: SocialPlatform): Observable<OAuthInitiateResponse> {
    return this.http.post<OAuthInitiateResponse>(`/api/integrations/${platform}/initiate`, {});
  }

  deleteIntegration(integrationId: string): Observable<void> {
    return this.http.delete<void>(`/api/integrations/${integrationId}`);
  }
}
