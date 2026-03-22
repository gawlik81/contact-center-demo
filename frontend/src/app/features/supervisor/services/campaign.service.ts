import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  Campaign,
  CreateCampaignRequest,
  PagedResponse,
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
}
