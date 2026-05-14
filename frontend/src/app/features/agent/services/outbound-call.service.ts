import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class OutboundCallService {
  private readonly http = inject(HttpClient);

  call(phoneNumber: string, customerId: string): Observable<{ contactId: string; callId: string }> {
    return this.http.post<{ contactId: string; callId: string }>('/api/telephony/calls/outbound', {
      phoneNumber,
      customerId,
    });
  }
}
