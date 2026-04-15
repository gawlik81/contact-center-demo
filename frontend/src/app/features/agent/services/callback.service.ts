import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { PagedResponse } from '../../../core/models/paged-response.model';
import {
  CallbackListItem,
  CallbackListParams,
  UpdateCallbackRequest,
} from '../models/callback.model';

@Injectable({ providedIn: 'root' })
export class CallbackService {
  private readonly http = inject(HttpClient);

  listCallbacks(params: CallbackListParams): Observable<PagedResponse<CallbackListItem>> {
    let httpParams = new HttpParams();
    if (params.status) httpParams = httpParams.set('status', params.status);
    if (params.sortDir) httpParams = httpParams.set('sortDir', params.sortDir);
    if (params.page !== undefined) httpParams = httpParams.set('page', params.page.toString());
    if (params.size !== undefined) httpParams = httpParams.set('size', params.size.toString());
    if (params.agentId) httpParams = httpParams.set('agentId', params.agentId);
    return this.http.get<PagedResponse<CallbackListItem>>(
      `${environment.apiUrl}/dialer/callbacks`,
      {
        params: httpParams,
      },
    );
  }

  updateCallback(callbackId: string, req: UpdateCallbackRequest): Observable<CallbackListItem> {
    return this.http.patch<CallbackListItem>(
      `${environment.apiUrl}/dialer/callbacks/${callbackId}`,
      req,
    );
  }

  cancelCallback(callbackId: string): Observable<void> {
    return this.http.delete<void>(`${environment.apiUrl}/dialer/callbacks/${callbackId}`);
  }
}
