import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { catchError, EMPTY, Observable, tap } from 'rxjs';
import { environment } from '../../../../environments/environment';
import { AgentStatus } from '../models/agent-status.model';
import { NotificationService } from '../../../core/services/notification.service';
import { WebSocketService } from '../../../core/services/websocket.service';

@Injectable({ providedIn: 'root' })
export class AgentStatusService {
  private readonly http = inject(HttpClient);
  private readonly notifications = inject(NotificationService);
  private readonly ws = inject(WebSocketService);

  readonly currentStatus = signal<AgentStatus>('OFFLINE');
  readonly isChanging = signal(false);

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
