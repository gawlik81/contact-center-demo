import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, catchError, of } from 'rxjs';
import { environment } from '../../../../environments/environment';

export interface PublicTenant {
  id: string;
  name: string;
}

// TODO FE-009: Backend endpoint GET /api/public/tenants must be implemented.
// Until then this service falls back to an empty list so the login form
// renders without hardcoded UUIDs. Implement the endpoint in Spring Boot
// with @PermitAll / PUBLIC_PATH_PREFIXES before enabling in production.
@Injectable({ providedIn: 'root' })
export class PublicTenantService {
  private readonly http = inject(HttpClient);
  private readonly url = `${environment.apiUrl}/public/tenants`;

  getTenants(): Observable<PublicTenant[]> {
    return this.http
      .get<PublicTenant[]>(this.url)
      .pipe(catchError(() => of<PublicTenant[]>([])));
  }
}
