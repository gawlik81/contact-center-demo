import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  AgentGroup,
  AgentGroupMembers,
  QueueAssignment,
  UpdateQueueAssignmentRequest,
} from '../models/agent-group.model';
import { PagedResponse } from '../models/paged-response.model';

@Injectable({ providedIn: 'root' })
export class AgentGroupService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = `${environment.apiUrl}/agent-groups`;

  listGroups(params?: {
    name?: string;
    page?: number;
    size?: number;
  }): Observable<PagedResponse<AgentGroup>> {
    let httpParams = new HttpParams();

    if (params?.name && params.name.trim()) {
      httpParams = httpParams.set('name', params.name.trim());
    }
    if (params?.page !== undefined) {
      httpParams = httpParams.set('page', params.page.toString());
    }
    if (params?.size !== undefined) {
      httpParams = httpParams.set('size', params.size.toString());
    }

    return this.http.get<PagedResponse<AgentGroup>>(this.baseUrl, { params: httpParams });
  }

  createGroup(name: string): Observable<AgentGroup> {
    return this.http.post<AgentGroup>(this.baseUrl, { name });
  }

  updateGroup(groupId: string, name: string): Observable<AgentGroup> {
    return this.http.patch<AgentGroup>(`${this.baseUrl}/${groupId}`, { name });
  }

  deleteGroup(groupId: string): Observable<void> {
    return this.http.delete<void>(`${this.baseUrl}/${groupId}`);
  }

  getGroupMembers(groupId: string): Observable<AgentGroupMembers> {
    return this.http.get<AgentGroupMembers>(`${this.baseUrl}/${groupId}/members`);
  }

  replaceGroupMembers(groupId: string, agentIds: string[]): Observable<AgentGroupMembers> {
    return this.http.put<AgentGroupMembers>(`${this.baseUrl}/${groupId}/members`, { agentIds });
  }

  getQueueAssignment(queueId: string): Observable<QueueAssignment> {
    return this.http.get<QueueAssignment>(`${environment.apiUrl}/queues/${queueId}/assignment`);
  }

  updateQueueAssignment(
    queueId: string,
    req: UpdateQueueAssignmentRequest,
  ): Observable<QueueAssignment> {
    return this.http.put<QueueAssignment>(
      `${environment.apiUrl}/queues/${queueId}/assignment`,
      req,
    );
  }
}
