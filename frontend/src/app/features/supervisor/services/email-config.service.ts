import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';

export interface EmailConfigResponse {
  imapHost: string;
  imapPort: number;
  imapSsl: boolean;
  smtpHost: string;
  smtpPort: number;
  smtpSsl: boolean;
  username: string;
  hasPassword: boolean;
  pollIntervalSeconds: number;
  emailEnabled: boolean;
  defaultQueueId: string | null;
}

export interface EmailConfigRequest {
  imapHost: string;
  imapPort: number;
  imapSsl: boolean;
  smtpHost: string;
  smtpPort: number;
  smtpSsl: boolean;
  username: string;
  password?: string | null;
  pollIntervalSeconds: number;
  emailEnabled: boolean;
  defaultQueueId: string | null;
}

export interface TestConnectionResponse {
  success: boolean;
  message: string;
}

@Injectable({ providedIn: 'root' })
export class EmailConfigService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/email/config`;

  getConfig(): Observable<EmailConfigResponse> {
    return this.http.get<EmailConfigResponse>(this.baseUrl);
  }

  updateConfig(req: EmailConfigRequest): Observable<EmailConfigResponse> {
    return this.http.put<EmailConfigResponse>(this.baseUrl, req);
  }

  testConnection(req: EmailConfigRequest): Observable<TestConnectionResponse> {
    return this.http.post<TestConnectionResponse>(`${this.baseUrl}/test`, req);
  }
}
