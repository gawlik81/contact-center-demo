import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { CreateQueueRequest, Queue, UpdateQueueRequest } from '../models/queue.model';
import { PagedResponse } from '../../../core/models/paged-response.model';

@Injectable({ providedIn: 'root' })
export class QueueService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/queues`;

  getQueues(page: number, size: number, name?: string): Observable<PagedResponse<Queue>> {
    let params = new HttpParams().set('page', page.toString()).set('size', size.toString());

    if (name && name.trim()) {
      params = params.set('name', name.trim());
    }

    return this.http.get<PagedResponse<Queue>>(this.baseUrl, { params });
  }

  getQueue(id: string): Observable<Queue> {
    return this.http.get<Queue>(`${this.baseUrl}/${id}`);
  }

  createQueue(req: CreateQueueRequest): Observable<Queue> {
    return this.http.post<Queue>(this.baseUrl, req);
  }

  updateQueue(id: string, req: UpdateQueueRequest): Observable<Queue> {
    return this.http.patch<Queue>(`${this.baseUrl}/${id}`, req);
  }

  deleteQueue(id: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${id}`);
  }

  getRoutingStrategies(): Observable<string[]> {
    return this.http.get<string[]>(`${this.baseUrl}/routing-strategies`);
  }
}
