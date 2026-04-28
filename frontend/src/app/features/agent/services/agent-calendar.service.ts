import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  AgentBreakRequest,
  AgentBreakResponse,
  AgentCalendarResponse,
} from '../models/agent-calendar.model';

@Injectable({ providedIn: 'root' })
export class AgentCalendarService {
  private readonly http = inject(HttpClient);

  getCalendar(from?: Date, to?: Date): Observable<AgentCalendarResponse> {
    let params = new HttpParams();
    if (from) {
      params = params.set('from', from.toISOString());
    }
    if (to) {
      params = params.set('to', to.toISOString());
    }
    return this.http.get<AgentCalendarResponse>(`${environment.apiUrl}/agent/calendar`, { params });
  }

  addBreak(req: AgentBreakRequest): Observable<AgentBreakResponse> {
    return this.http.post<AgentBreakResponse>(`${environment.apiUrl}/agent/breaks`, req);
  }

  updateBreak(id: string, req: AgentBreakRequest): Observable<AgentBreakResponse> {
    return this.http.put<AgentBreakResponse>(`${environment.apiUrl}/agent/breaks/${id}`, req);
  }

  cancelBreak(id: string): Observable<void> {
    return this.http.delete<void>(`${environment.apiUrl}/agent/breaks/${id}`);
  }
}
