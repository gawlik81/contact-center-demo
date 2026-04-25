import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  ContactResponse,
  EmailPreviewResponse,
  RecordingUrlResponse,
  RelatedItem,
  SetDispositionRequest,
} from '../../../core/models/contact.model';
import { PagedResponse } from '../../../core/models/paged-response.model';

export type {
  ContactResponse,
  EmailPreviewResponse,
  RecordingUrlResponse,
  RelatedItem,
  SetDispositionRequest,
};

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

  getEmailPreview(contactId: string): Observable<EmailPreviewResponse> {
    return this.http.get<EmailPreviewResponse>(
      `${environment.apiUrl}/contacts/${contactId}/email-preview`,
    );
  }

  getRelatedContacts(contactId: string): Observable<RelatedItem[]> {
    return this.http.get<RelatedItem[]>(`${environment.apiUrl}/contacts/${contactId}/related`);
  }

  /**
   * Confirms that the agent has opened/accepted an EMAIL or CHAT contact.
   * Transitions the contact from ASSIGNED → ACTIVE on the backend, which stops
   * ContactAssignmentMonitor from re-queuing it.
   */
  acceptContact(contactId: string): Observable<ContactResponse> {
    return this.http.post<ContactResponse>(
      `${environment.apiUrl}/contacts/${contactId}/accept`,
      {},
    );
  }

  /**
   * Abandons an EMAIL or CHAT contact without sending a reply.
   * Transitions the contact to ABANDONED on the backend.
   */
  abandonContact(contactId: string): Observable<ContactResponse> {
    return this.http.post<ContactResponse>(
      `${environment.apiUrl}/contacts/${contactId}/abandon`,
      {},
    );
  }

  getContacts(
    filters: ContactFilterParams,
    page: number,
    size: number,
  ): Observable<PagedResponse<ContactResponse>> {
    let params = new HttpParams().set('page', page.toString()).set('size', size.toString());

    if (filters.dateFrom) params = params.set('dateFrom', filters.dateFrom);
    if (filters.dateTo) params = params.set('dateTo', filters.dateTo);
    if (filters.channel) params = params.set('channel', filters.channel);
    if (filters.status) params = params.set('status', filters.status);
    if (filters.queueId) params = params.set('queueId', filters.queueId);
    if (filters.campaignId) params = params.set('campaignId', filters.campaignId);
    if (filters.remoteAddress) params = params.set('remoteAddress', filters.remoteAddress);
    if (filters.durationMin !== undefined)
      params = params.set('durationMin', filters.durationMin.toString());
    if (filters.durationMax !== undefined)
      params = params.set('durationMax', filters.durationMax.toString());

    return this.http.get<PagedResponse<ContactResponse>>(`${environment.apiUrl}/contacts`, {
      params,
    });
  }
}
