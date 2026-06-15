import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { CustomerSearchResponse } from '../models/customer-search.model';
import { ContactResponse } from '../../../core/models/contact.model';

interface PagedResponse<T> {
  content: T[];
  totalElements: number;
}

@Injectable({ providedIn: 'root' })
export class CustomerSearchService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/customers`;
  private readonly contactsUrl = `${environment.apiUrl}/contacts`;

  search(query: string, limit = 20): Observable<CustomerSearchResponse> {
    const params = new HttpParams().set('q', query.trim()).set('size', limit.toString());

    return this.http.get<CustomerSearchResponse>(this.baseUrl, { params });
  }

  getLastContact(customerId: string): Observable<ContactResponse | null> {
    const params = new HttpParams().set('customerId', customerId).set('page', '0').set('size', '1');

    return this.http
      .get<PagedResponse<ContactResponse>>(this.contactsUrl, { params })
      .pipe(map((response) => response.content[0] ?? null));
  }
}
