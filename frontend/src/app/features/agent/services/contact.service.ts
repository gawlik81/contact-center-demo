import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { SKIP_ERROR_TOAST } from '../../../core/interceptors/error-handler.interceptor';
import {
  ContactEventResponse,
  ContactResponse,
  EmailPreviewResponse,
  RecordingUrlResponse,
  RelatedItem,
  SetDispositionRequest,
} from '../../../core/models/contact.model';
import { PagedResponse } from '../../../core/models/paged-response.model';

export type {
  ContactEventResponse,
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

  getContactEvents(contactId: string): Observable<ContactEventResponse[]> {
    return this.http.get<ContactEventResponse[]>(
      `${environment.apiUrl}/contacts/${contactId}/events`,
    );
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

  /**
   * Returns an EMAIL/SOCIAL/CHAT contact to the queue (agent cleared) so someone else can take
   * it. Idempotent when it is already queued; 404 = unknown contact, 409 = it is not (or no
   * longer) this agent's, or it is not releasable (PHONE, terminal state).
   * The caller shows its own, status-specific message, so the global error toast is skipped.
   */
  releaseContact(contactId: string): Observable<ContactResponse> {
    return this.http.post<ContactResponse>(
      `${environment.apiUrl}/contacts/${contactId}/release`,
      {},
      { context: new HttpContext().set(SKIP_ERROR_TOAST, true) },
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
