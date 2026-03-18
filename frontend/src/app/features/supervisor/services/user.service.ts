import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import {
  CreateUserRequest,
  PagedResponse,
  UpdateStatusRequest,
  UpdateUserRequest,
  UserListParams,
  UserResponse,
} from '../models/user.model';

@Injectable({ providedIn: 'root' })
export class UserService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/users`;

  getUsers(params: UserListParams): Observable<PagedResponse<UserResponse>> {
    let httpParams = new HttpParams()
      .set('page', params.page.toString())
      .set('size', params.size.toString());

    if (params.status) {
      httpParams = httpParams.set('status', params.status);
    }
    if (params.skill && params.skill.trim()) {
      httpParams = httpParams.set('skill', params.skill.trim());
    }
    if (params.role) {
      httpParams = httpParams.set('role', params.role);
    }
    if (params.search && params.search.trim()) {
      httpParams = httpParams.set('search', params.search.trim());
    }

    return this.http.get<PagedResponse<UserResponse>>(this.baseUrl, { params: httpParams });
  }

  getUser(id: string): Observable<UserResponse> {
    return this.http.get<UserResponse>(`${this.baseUrl}/${id}`);
  }

  createUser(req: CreateUserRequest): Observable<UserResponse> {
    return this.http.post<UserResponse>(this.baseUrl, req);
  }

  updateUser(id: string, req: UpdateUserRequest): Observable<UserResponse> {
    return this.http.patch<UserResponse>(`${this.baseUrl}/${id}`, req);
  }

  deleteUser(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  getSkills(): Observable<string[]> {
    return this.http.get<string[]>(`${this.baseUrl}/skills`);
  }

  updateStatus(id: string, req: UpdateStatusRequest): Observable<UserResponse> {
    return this.http.patch<UserResponse>(`${this.baseUrl}/${id}/status`, req);
  }

  forcePasswordReset(id: string): Observable<void> {
    return this.http.post<void>(`${environment.apiUrl}/auth/force-reset/${id}`, {});
  }
}
