import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import {
  ApplySetResponse,
  CreateDispositionSetItemRequest,
  CreateDispositionSetRequest,
  DispositionSet,
  DispositionSetDetail,
  DispositionSetItem,
  UpdateDispositionSetItemRequest,
  UpdateDispositionSetRequest,
} from '../models/disposition-set.model';

@Injectable({ providedIn: 'root' })
export class DispositionSetService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/disposition-sets';

  // Sets

  listSets(): Observable<DispositionSet[]> {
    return this.http.get<DispositionSet[]>(this.base);
  }

  getSet(setId: string): Observable<DispositionSetDetail> {
    return this.http.get<DispositionSetDetail>(`${this.base}/${setId}`);
  }

  createSet(req: CreateDispositionSetRequest): Observable<DispositionSet> {
    return this.http.post<DispositionSet>(this.base, req);
  }

  updateSet(setId: string, req: UpdateDispositionSetRequest): Observable<DispositionSet> {
    return this.http.put<DispositionSet>(`${this.base}/${setId}`, req);
  }

  deleteSet(setId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${setId}`);
  }

  // Items

  listItems(setId: string): Observable<DispositionSetItem[]> {
    return this.http.get<DispositionSetItem[]>(`${this.base}/${setId}/items`);
  }

  addItem(setId: string, req: CreateDispositionSetItemRequest): Observable<DispositionSetItem> {
    return this.http.post<DispositionSetItem>(`${this.base}/${setId}/items`, req);
  }

  updateItem(
    setId: string,
    itemId: string,
    req: UpdateDispositionSetItemRequest,
  ): Observable<DispositionSetItem> {
    return this.http.put<DispositionSetItem>(`${this.base}/${setId}/items/${itemId}`, req);
  }

  removeItem(setId: string, itemId: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${setId}/items/${itemId}`);
  }

  // Apply

  applyToCampaign(setId: string, campaignId: string): Observable<ApplySetResponse> {
    return this.http.post<ApplySetResponse>(
      `${this.base}/${setId}/apply-to-campaign/${campaignId}`,
      {},
    );
  }

  applyToQueue(setId: string, queueId: string): Observable<ApplySetResponse> {
    return this.http.post<ApplySetResponse>(`${this.base}/${setId}/apply-to-queue/${queueId}`, {});
  }
}
