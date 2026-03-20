import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';

export interface ContactResponse {
  id: string;
  tenantId: string;
  customerId: string | null;
  agentId: string;
  channel: string;
  status: string;
  direction: string;
  startedAt: string;
  endedAt: string | null;
  dispositionCode: string | null;
  notes: string | null;
}

export interface SetDispositionRequest {
  dispositionCode: string;
  notes: string;
}

@Injectable({ providedIn: 'root' })
export class ContactService {
  private readonly http = inject(HttpClient);

  setDisposition(
    contactId: string,
    dispositionCode: string,
    notes: string,
  ): Observable<ContactResponse> {
    const body: SetDispositionRequest = { dispositionCode, notes };
    return this.http.patch<ContactResponse>(
      `${environment.apiUrl}/contacts/${contactId}/disposition`,
      body,
    );
  }
}
