import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  AdminCreateUserRequest,
  AdminPagedResponse,
  AdminUpdateUserRequest,
  AdminUserListParams,
  AdminUserResponse,
} from '../models/admin-user.model';

@Injectable({ providedIn: 'root' })
export class AdminUserService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/admin/users`;

  getUsers(params: AdminUserListParams): Observable<AdminPagedResponse<AdminUserResponse>> {
    let httpParams = new HttpParams()
      .set('page', params.page.toString())
      .set('size', params.size.toString());

    if (params.tenantId) {
      httpParams = httpParams.set('tenantId', params.tenantId);
    }

    return this.http.get<AdminPagedResponse<AdminUserResponse>>(this.baseUrl, {
      params: httpParams,
    });
  }

  createUser(req: AdminCreateUserRequest): Observable<AdminUserResponse> {
    return this.http.post<AdminUserResponse>(this.baseUrl, req);
  }

  updateUser(id: string, req: AdminUpdateUserRequest): Observable<AdminUserResponse> {
    return this.http.patch<AdminUserResponse>(`${this.baseUrl}/${id}`, req);
  }

  deleteUser(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  forcePasswordReset(id: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${id}/force-password-reset`, {});
  }
}
