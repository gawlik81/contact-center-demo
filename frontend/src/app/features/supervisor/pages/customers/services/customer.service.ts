import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../../../environments/environment';
import {
  CustomerListParams,
  CustomerResponse,
  PagedResponse,
} from '../../../models/customer.model';
import { ContactResponse } from '../../../../../core/models/contact.model';

export interface ContactListParams {
  customerId: string;
  page: number;
  size: number;
}

@Injectable({ providedIn: 'root' })
export class CustomerService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/customers`;
  private readonly contactsUrl = `${environment.apiUrl}/contacts`;

  getCustomers(params: CustomerListParams): Observable<PagedResponse<CustomerResponse>> {
    let httpParams = new HttpParams()
      .set('page', params.page.toString())
      .set('size', params.size.toString());

    if (params.q && params.q.trim().length >= 2) {
      httpParams = httpParams.set('q', params.q.trim());
    }
    if (params.sort) {
      httpParams = httpParams.set('sort', params.sort);
    }

    return this.http.get<PagedResponse<CustomerResponse>>(this.baseUrl, {
      params: httpParams,
    });
  }

  getCustomer(id: string): Observable<CustomerResponse> {
    return this.http.get<CustomerResponse>(`${this.baseUrl}/${id}`);
  }

  getCustomerContacts(params: ContactListParams): Observable<PagedResponse<ContactResponse>> {
    const httpParams = new HttpParams()
      .set('customerId', params.customerId)
      .set('page', params.page.toString())
      .set('size', Math.min(params.size, 100).toString());

    return this.http.get<PagedResponse<ContactResponse>>(this.contactsUrl, {
      params: httpParams,
    });
  }

  createCustomer(payload: {
    firstName?: string;
    lastName?: string;
    phone: string[];
    email: string[];
  }): Observable<CustomerResponse> {
    return this.http.post<CustomerResponse>(this.baseUrl, payload);
  }

  deleteCustomer(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }
}
