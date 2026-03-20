import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { ContactResponse, SetDispositionRequest } from '../models/contact.model';

export type { ContactResponse, SetDispositionRequest };

@Injectable({ providedIn: 'root' })
export class ContactService {
  private readonly http = inject(HttpClient);

  setDisposition(
    contactId: string,
    dispositionCode: string,
    notes: string,
  ): Observable<ContactResponse> {
    const body: SetDispositionRequest = { dispositionCode, notes };
    return this.http.patch<ContactResponse>(
      `${environment.apiUrl}/contacts/${contactId}/disposition`,
      body,
    );
  }
}
