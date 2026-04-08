import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ManualCallResponse, ManualCampaignGroup } from '../../supervisor/models/campaign.model';

@Injectable({ providedIn: 'root' })
export class DialerService {
  private readonly http = inject(HttpClient);
  private readonly API = '/api/dialer';

  manualCall(campaignId: string, recordId: string): Observable<ManualCallResponse> {
    return this.http.post<ManualCallResponse>(`${this.API}/manual/call`, {
      campaignId,
      recordId,
    });
  }

  getManualCampaignRecords(): Observable<ManualCampaignGroup[]> {
    return this.http.get<ManualCampaignGroup[]>(`${this.API}/manual/records`);
  }
}
