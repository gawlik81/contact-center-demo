import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  CreateInboundCallbackRequest,
  RescheduleCallbackRequest,
  ScheduledCallbackDto,
} from '../../agent/models/callback.model';

@Injectable({ providedIn: 'root' })
export class DialerService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/dialer`;

  rescheduleCallback(
    callbackId: string,
    req: RescheduleCallbackRequest,
  ): Observable<ScheduledCallbackDto> {
    return this.http.put<ScheduledCallbackDto>(`${this.baseUrl}/callbacks/${callbackId}`, req);
  }

  createInboundCallback(
    contactId: string,
    req: CreateInboundCallbackRequest,
  ): Observable<ScheduledCallbackDto> {
    return this.http.post<ScheduledCallbackDto>(`/api/contacts/${contactId}/callback`, req);
  }
}
