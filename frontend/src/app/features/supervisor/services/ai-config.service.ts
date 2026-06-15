import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { environment } from '../../../../environments/environment';

export type AiProvider = 'ANTHROPIC' | 'OPENAI' | 'AZURE_OPENAI' | 'OPENROUTER';

export interface AiConfigResponse {
  id: string;
  tenantId: string;
  provider: AiProvider;
  apiKeyMasked: string;
  modelName: string;
  azureEndpoint: string | null;
  azureDeploymentName: string | null;
  summaryPromptTemplate: string | null;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AiConfigRequest {
  provider: AiProvider;
  apiKey?: string | null;
  modelName: string;
  azureEndpoint?: string | null;
  azureDeploymentName?: string | null;
  summaryPromptTemplate?: string | null;
}

@Injectable({ providedIn: 'root' })
export class AiConfigService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/supervisor/ai-config`;

  getConfig(): Observable<AiConfigResponse | null> {
    return this.http
      .get<AiConfigResponse>(this.baseUrl, { observe: 'response' })
      .pipe(map((r) => (r.status === 204 ? null : r.body)));
  }

  saveConfig(data: AiConfigRequest): Observable<AiConfigResponse> {
    return this.http.put<AiConfigResponse>(this.baseUrl, data);
  }

  deleteConfig(): Observable<void> {
    return this.http.delete<void>(this.baseUrl);
  }
}
