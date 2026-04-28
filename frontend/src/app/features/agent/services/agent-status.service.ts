import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { catchError, EMPTY, filter, Observable, switchMap, tap } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { AgentStatus } from '../models/agent-status.model';
import { NotificationService } from '../../../core/services/notification.service';
import { WebSocketService } from '../../../core/services/websocket.service';
import { AgentStatusChangedPayload, WsEvent } from '../models/ws-event.model';

interface UserResponse {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  role: string;
  status: string;
}

const ACTIVE_STATUSES: ReadonlySet<AgentStatus> = new Set<AgentStatus>([
  'AVAILABLE',
  'BUSY',
  'BREAK',
  'AFTER_CONTACT',
]);

@Injectable({ providedIn: 'root' })
export class AgentStatusService {
  private readonly http = inject(HttpClient);
  private readonly notifications = inject(NotificationService);
  private readonly ws = inject(WebSocketService);

  readonly currentStatus = signal<AgentStatus>('OFFLINE');
  readonly isChanging = signal(false);

  private currentUserId: string | null = null;

  /**
   * Fetches the current user profile via GET /api/users/me and initialises the agent status.
   * Also subscribes to AGENT_STATUS_CHANGED WebSocket events so server-side status changes
   * (e.g. automatic break activation by the scheduler) are reflected immediately in the UI.
   */
  initStatus(): void {
    this.http
      .get<UserResponse>(`${environment.apiUrl}/users/me`)
      .pipe(
        switchMap((user) => {
          this.currentUserId = user.id;
          const status = user?.status as AgentStatus | undefined;
          if (status && ACTIVE_STATUSES.has(status)) {
            this.currentStatus.set(status);
            return EMPTY;
          }
          return this.changeStatus('AVAILABLE');
        }),
        catchError(() => {
          this.notifications.error('Nie udało się pobrać statusu agenta.');
          return EMPTY;
        }),
      )
      .subscribe();

    this.ws.events$
      .pipe(filter((e: WsEvent) => e.eventType === 'AGENT_STATUS_CHANGED'))
      .subscribe((e) => {
        const payload = e.payload as AgentStatusChangedPayload;
        if (payload.agentId === this.currentUserId) {
          this.currentStatus.set(payload.status);
        }
      });
  }

  /**
   * Sends an HTTP PATCH to update the agent status and notifies supervisors via WebSocket.
   * Returns an Observable<void> that completes on success or errors on failure.
   * Callers should subscribe and handle errors independently.
   */
  changeStatus(status: AgentStatus): Observable<void> {
    if (this.isChanging()) {
      return EMPTY;
    }
    this.isChanging.set(true);

    return this.http.patch<void>(`${environment.apiUrl}/users/me/status`, { status }).pipe(
      tap(() => {
        this.currentStatus.set(status);
        // Notify backend via WebSocket so supervisors get real-time update
        this.ws.publish('/app/agent/status', { status });
        this.isChanging.set(false);
      }),
      catchError(() => {
        this.notifications.error('Nie udało się zmienić statusu. Spróbuj ponownie.');
        this.isChanging.set(false);
        return EMPTY;
      }),
    );
  }
}
