import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { ManualCallbackRequest, ManualCallbackResponse } from '../models/manual-callback.model';

@Injectable({ providedIn: 'root' })
export class ManualCallbackService {
  private readonly http = inject(HttpClient);

  createManualCallback(request: ManualCallbackRequest): Observable<ManualCallbackResponse> {
    return this.http.post<ManualCallbackResponse>(
      `${environment.apiUrl}/callbacks/manual`,
      request,
    );
  }
}
