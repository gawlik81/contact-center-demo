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
import { DeduplicationMode, CustomerImportStatus } from '../customer-import.model';

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
    externalId?: string;
    phone: string[];
    email: string[];
  }): Observable<CustomerResponse> {
    return this.http.post<CustomerResponse>(this.baseUrl, payload);
  }

  updateCustomer(
    id: string,
    payload: {
      firstName?: string;
      lastName?: string;
      externalId?: string;
      phone?: string[];
      email?: string[];
      customFields?: Record<string, unknown>;
      gdprConsent?: Record<string, unknown>;
    },
  ): Observable<CustomerResponse> {
    return this.http.patch<CustomerResponse>(`${this.baseUrl}/${id}`, payload);
  }

  deleteCustomer(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  importCsv(
    file: File,
    separator: string,
    quoteChar: string,
    deduplication: DeduplicationMode,
    columnMapping: Record<string, unknown>,
  ): Observable<{ jobId: string }> {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('columnMapping', JSON.stringify(columnMapping));

    const params = new HttpParams()
      .set('separator', separator)
      .set('quoteChar', quoteChar)
      .set('deduplication', deduplication);

    return this.http.post<{ jobId: string }>(`${this.baseUrl}/import`, formData, { params });
  }

  importJson(file: File, deduplication: DeduplicationMode): Observable<{ jobId: string }> {
    const formData = new FormData();
    formData.append('file', file);
    const params = new HttpParams().set('deduplication', deduplication);

    return this.http.post<{ jobId: string }>(`${this.baseUrl}/import/json`, formData, { params });
  }

  getImportStatus(jobId: string): Observable<CustomerImportStatus> {
    return this.http.get<CustomerImportStatus>(`${this.baseUrl}/import/${jobId}`);
  }

  downloadImportErrors(jobId: string): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/import/${jobId}/errors`, { responseType: 'blob' });
  }
}
