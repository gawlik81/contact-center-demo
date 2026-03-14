import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CreateTenantRequest,
  NameAvailabilityResponse,
  Tenant,
  TenantListParams,
} from './tenant.model';

@Injectable({ providedIn: 'root' })
export class TenantService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/tenants`;

  getTenants(params: TenantListParams): Observable<Tenant[]> {
    let httpParams = new HttpParams();

    if (params.name && params.name.trim()) {
      httpParams = httpParams.set('name', params.name.trim());
    }
    if (params.status) {
      httpParams = httpParams.set('status', params.status);
    }

    return this.http.get<Tenant[]>(this.baseUrl, { params: httpParams });
  }

  createTenant(request: CreateTenantRequest): Observable<Tenant> {
    return this.http.post<Tenant>(this.baseUrl, request);
  }

  deactivateTenant(id: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${id}/deactivate`, {});
  }

  checkNameAvailability(name: string): Observable<NameAvailabilityResponse> {
    const params = new HttpParams().set('name', name);
    return this.http.get<NameAvailabilityResponse>(`${this.baseUrl}/check-name`, { params });
  }
}
