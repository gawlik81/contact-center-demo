import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  AvailableDisposition,
  CreateCustomDispositionRequest,
  CustomDisposition,
  UpdateCustomDispositionRequest,
} from '../models/custom-disposition.model';

@Injectable({ providedIn: 'root' })
export class CustomDispositionService {
  private readonly http = inject(HttpClient);

  // Supervisor — campaign scope

  listForCampaign(campaignId: string): Observable<CustomDisposition[]> {
    return this.http.get<CustomDisposition[]>(`/api/dispositions/campaigns/${campaignId}`);
  }

  createForCampaign(
    campaignId: string,
    req: CreateCustomDispositionRequest,
  ): Observable<CustomDisposition> {
    return this.http.post<CustomDisposition>(`/api/dispositions/campaigns/${campaignId}`, req);
  }

  updateForCampaign(
    campaignId: string,
    id: string,
    req: UpdateCustomDispositionRequest,
  ): Observable<CustomDisposition> {
    return this.http.put<CustomDisposition>(`/api/dispositions/campaigns/${campaignId}/${id}`, req);
  }

  deleteFromCampaign(campaignId: string, id: string): Observable<void> {
    return this.http.delete<void>(`/api/dispositions/campaigns/${campaignId}/${id}`);
  }

  // Supervisor — queue scope

  listForQueue(queueId: string): Observable<CustomDisposition[]> {
    return this.http.get<CustomDisposition[]>(`/api/dispositions/queues/${queueId}`);
  }

  createForQueue(
    queueId: string,
    req: CreateCustomDispositionRequest,
  ): Observable<CustomDisposition> {
    return this.http.post<CustomDisposition>(`/api/dispositions/queues/${queueId}`, req);
  }

  updateForQueue(
    queueId: string,
    id: string,
    req: UpdateCustomDispositionRequest,
  ): Observable<CustomDisposition> {
    return this.http.put<CustomDisposition>(`/api/dispositions/queues/${queueId}/${id}`, req);
  }

  deleteFromQueue(queueId: string, id: string): Observable<void> {
    return this.http.delete<void>(`/api/dispositions/queues/${queueId}/${id}`);
  }

  // Agent — available dispositions for a contact

  getAvailableDispositions(contactId: string): Observable<AvailableDisposition[]> {
    return this.http.get<AvailableDisposition[]>(
      `/api/contacts/${contactId}/available-dispositions`,
    );
  }
}
