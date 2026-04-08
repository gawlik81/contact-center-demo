import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { ContactResponse, RecordingUrlResponse, SetDispositionRequest } from '../models/contact.model';
import { PagedResponse } from '../../../core/models/paged-response.model';

export type { ContactResponse, RecordingUrlResponse, SetDispositionRequest };

export interface ContactFilterParams {
  dateFrom?: string;
  dateTo?: string;
  channel?: string;
  status?: string;
  queueId?: string;
  campaignId?: string;
  remoteAddress?: string;
  durationMin?: number;
  durationMax?: number;
}

@Injectable({ providedIn: 'root' })
export class ContactService {
  private readonly http = inject(HttpClient);

  getContact(contactId: string): Observable<ContactResponse> {
    return this.http.get<ContactResponse>(`${environment.apiUrl}/contacts/${contactId}`);
  }

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

  getRecordingUrl(contactId: string): Observable<RecordingUrlResponse> {
    return this.http.get<RecordingUrlResponse>(
      `${environment.apiUrl}/contacts/${contactId}/recording`,
    );
  }

  getContacts(
    filters: ContactFilterParams,
    page: number,
    size: number,
  ): Observable<PagedResponse<ContactResponse>> {
    let params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());

    if (filters.dateFrom) params = params.set('dateFrom', filters.dateFrom);
    if (filters.dateTo) params = params.set('dateTo', filters.dateTo);
    if (filters.channel) params = params.set('channel', filters.channel);
    if (filters.status) params = params.set('status', filters.status);
    if (filters.queueId) params = params.set('queueId', filters.queueId);
    if (filters.campaignId) params = params.set('campaignId', filters.campaignId);
    if (filters.remoteAddress) params = params.set('remoteAddress', filters.remoteAddress);
    if (filters.durationMin !== undefined) params = params.set('durationMin', filters.durationMin.toString());
    if (filters.durationMax !== undefined) params = params.set('durationMax', filters.durationMax.toString());

    return this.http.get<PagedResponse<ContactResponse>>(`${environment.apiUrl}/contacts`, { params });
  }
}
