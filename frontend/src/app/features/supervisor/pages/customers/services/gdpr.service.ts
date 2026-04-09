import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../../../environments/environment';

@Injectable({ providedIn: 'root' })
export class GdprService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/customers`;

  exportData(customerId: string): Observable<Blob> {
    return this.http.post(`${this.baseUrl}/${customerId}/gdpr/export`, null, {
      responseType: 'blob',
    });
  }

  anonymize(customerId: string): Observable<void> {
    return this.http.post<void>(`${this.baseUrl}/${customerId}/gdpr/anonymize`, null);
  }
}
