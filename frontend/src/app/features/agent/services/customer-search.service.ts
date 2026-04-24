import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { CustomerSearchResponse } from '../models/customer-search.model';

@Injectable({ providedIn: 'root' })
export class CustomerSearchService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/customers`;

  /**
   * Searches customers by a free-text query.
   * Calls GET /api/customers?search=<query>&limit=<limit>
   *
   * Caller must ensure query.length >= 2 before invoking this method.
   */
  search(query: string, limit = 20): Observable<CustomerSearchResponse> {
    const params = new HttpParams().set('q', query.trim()).set('size', limit.toString());

    return this.http.get<CustomerSearchResponse>(this.baseUrl, { params });
  }
}
