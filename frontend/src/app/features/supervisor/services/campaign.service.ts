import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  Campaign,
  CampaignAssignment,
  CampaignContact,
  ContactAttempt,
  CreateCampaignRequest,
  ImportJobStartResponse,
  ImportJobStatus,
  PagedResponse,
  UpdateCampaignAssignmentRequest,
  UpdateCampaignRequest,
} from '../models/campaign.model';

@Injectable({ providedIn: 'root' })
export class CampaignService {
  private readonly http = inject(HttpClient);
  private readonly API = '/api/campaigns';

  getCampaigns(page = 0, size = 20): Observable<PagedResponse<Campaign>> {
    const params = new HttpParams().set('page', page.toString()).set('size', size.toString());
    return this.http.get<PagedResponse<Campaign>>(this.API, { params });
  }

  getCampaign(id: string): Observable<Campaign> {
    return this.http.get<Campaign>(`${this.API}/${id}`);
  }

  createCampaign(data: CreateCampaignRequest): Observable<Campaign> {
    return this.http.post<Campaign>(this.API, data);
  }

  updateCampaign(id: string, data: UpdateCampaignRequest): Observable<Campaign> {
    return this.http.patch<Campaign>(`${this.API}/${id}`, data);
  }

  startCampaign(id: string): Observable<Campaign> {
    return this.http.post<Campaign>(`${this.API}/${id}/start`, {});
  }

  pauseCampaign(id: string): Observable<Campaign> {
    return this.http.post<Campaign>(`${this.API}/${id}/pause`, {});
  }

  stopCampaign(id: string): Observable<Campaign> {
    return this.http.post<Campaign>(`${this.API}/${id}/stop`, {});
  }

  revertToDraft(id: string): Observable<Campaign> {
    return this.http.post<Campaign>(`${this.API}/${id}/draft`, {});
  }

  importContacts(
    campaignId: string,
    file: File,
    skipDuplicates: boolean,
    columnSeparator: string,
    quoteChar: string,
    columnMapping: string,
  ): Observable<ImportJobStartResponse> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('skipDuplicates', String(skipDuplicates));
    formData.append('columnSeparator', columnSeparator);
    formData.append('quoteChar', quoteChar);
    formData.append('columnMapping', columnMapping);
    return this.http.post<ImportJobStartResponse>(
      `${this.API}/${campaignId}/contacts/import`,
      formData,
    );
  }

  getImportStatus(campaignId: string, jobId: string): Observable<ImportJobStatus> {
    return this.http.get<ImportJobStatus>(`${this.API}/${campaignId}/import-status/${jobId}`);
  }

  getCampaignContacts(
    campaignId: string,
    page = 0,
    size = 50,
    status?: string,
  ): Observable<PagedResponse<CampaignContact>> {
    let params = new HttpParams().set('page', page.toString()).set('size', size.toString());
    if (status) {
      params = params.set('status', status);
    }
    return this.http.get<PagedResponse<CampaignContact>>(`${this.API}/${campaignId}/contacts`, {
      params,
    });
  }

  getContactAttempts(campaignId: string, recordId: string): Observable<ContactAttempt[]> {
    return this.http.get<ContactAttempt[]>(
      `${this.API}/${campaignId}/contacts/${recordId}/attempts`,
    );
  }

  getCampaignAssignment(campaignId: string): Observable<CampaignAssignment> {
    return this.http.get<CampaignAssignment>(`${this.API}/${campaignId}/assignment`);
  }

  updateCampaignAssignment(
    campaignId: string,
    req: UpdateCampaignAssignmentRequest,
  ): Observable<CampaignAssignment> {
    return this.http.put<CampaignAssignment>(`${this.API}/${campaignId}/assignment`, req);
  }
}
