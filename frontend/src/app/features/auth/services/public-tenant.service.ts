import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, of } from 'rxjs';
import { environment } from '../../../../environments/environment';

export interface PublicTenant {
  id: string;
  name: string;
}

@Injectable({ providedIn: 'root' })
export class PublicTenantService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/public`;

  /** @deprecated Use getTenantsByEmail() – kept for backward compatibility. */
  getTenants(): Observable<PublicTenant[]> {
    return this.http
      .get<PublicTenant[]>(`${this.baseUrl}/tenants`)
      .pipe(catchError(() => of<PublicTenant[]>([])));
  }

  /**
   * Returns tenants associated with the given email address.
   * Calls POST /api/public/tenants-by-email.
   * On any error falls back to an empty list – the login flow continues
   * and the backend will return 401 if the credentials are invalid.
   */
  getTenantsByEmail(email: string): Observable<PublicTenant[]> {
    return this.http
      .post<PublicTenant[]>(`${this.baseUrl}/tenants-by-email`, { email })
      .pipe(catchError(() => of<PublicTenant[]>([])));
  }
}
