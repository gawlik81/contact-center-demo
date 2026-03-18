import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  CreateTenantRequest,
  NameAvailabilityResponse,
  PagedResponse,
  Tenant,
  TenantListParams,
  UpdateTenantRequest,
} from './tenant.model';

@Injectable({ providedIn: 'root' })
export class TenantService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/tenants`;

  getTenants(params: TenantListParams): Observable<PagedResponse<Tenant>> {
    let httpParams = new HttpParams()
      .set('page', String(params.page))
      .set('size', String(params.size));

    if (params.name && params.name.trim()) {
      httpParams = httpParams.set('name', params.name.trim());
    }
    if (params.status) {
      httpParams = httpParams.set('status', params.status);
    }

    return this.http.get<PagedResponse<Tenant>>(this.baseUrl, { params: httpParams });
  }

  createTenant(request: CreateTenantRequest): Observable<Tenant> {
    return this.http.post<Tenant>(this.baseUrl, request);
  }

  updateTenant(id: string, request: UpdateTenantRequest): Observable<Tenant> {
    return this.http.patch<Tenant>(`${this.baseUrl}/${id}`, request);
  }

  deactivateTenant(id: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${id}/deactivate`, {});
  }

  checkNameAvailability(name: string): Observable<NameAvailabilityResponse> {
    const params = new HttpParams().set('name', name);
    return this.http.get<NameAvailabilityResponse>(`${this.baseUrl}/check-name`, { params });
  }

  /** Sprawdza dostępność nazwy przy edycji – wyklucza tenant o podanym ID z porównania. */
  checkNameAvailabilityForUpdate(
    tenantId: string,
    name: string,
  ): Observable<NameAvailabilityResponse> {
    const params = new HttpParams().set('name', name);
    return this.http.get<NameAvailabilityResponse>(`${this.baseUrl}/${tenantId}/check-name`, {
      params,
    });
  }
}
