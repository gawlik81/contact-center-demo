import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { AgentReportFilters, AgentReportRow } from '../models/report.model';
import { PagedResponse } from '../models/user.model';

@Injectable({ providedIn: 'root' })
export class ReportsService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/reports`;

  getAgentReport(
    filters: AgentReportFilters,
    page: number,
    size: number,
  ): Observable<PagedResponse<AgentReportRow>> {
    const params = this.buildParams(filters)
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get<PagedResponse<AgentReportRow>>(`${this.baseUrl}/agents`, { params });
  }

  exportCsv(filters: AgentReportFilters): Observable<Blob> {
    const params = this.buildParams(filters);
    return this.http.get(`${this.baseUrl}/agents/export`, {
      params,
      responseType: 'blob',
    });
  }

  exportXlsx(filters: AgentReportFilters): Observable<Blob> {
    const params = this.buildParams(filters);
    return this.http.get(`${this.baseUrl}/agents/export/xlsx`, {
      params,
      responseType: 'blob',
    });
  }

  private buildParams(filters: AgentReportFilters): HttpParams {
    let params = new HttpParams().set('dateFrom', filters.dateFrom).set('dateTo', filters.dateTo);

    if (filters.agentId) {
      params = params.set('agentId', filters.agentId);
    }
    if (filters.queueId) {
      params = params.set('queueId', filters.queueId);
    }
    if (filters.channel) {
      params = params.set('channel', filters.channel);
    }

    return params;
  }
}
