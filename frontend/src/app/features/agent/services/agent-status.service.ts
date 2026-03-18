import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { catchError, EMPTY, tap } from 'rxjs';
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

  changeStatus(status: AgentStatus): void {
    if (this.isChanging()) return;
    this.isChanging.set(true);

    this.http
      .patch<void>(`${environment.apiUrl}/users/me/status`, { status })
      .pipe(
        tap(() => {
          this.currentStatus.set(status);
          // Notify backend via WebSocket so supervisors get real-time update
          this.ws.publish('/app/agent/status', { status });
          this.isChanging.set(false);
        }),
        catchError(() => {
          this.notifications.error('Nie udalo sie zmienic statusu. Sprobuj ponownie.');
          this.isChanging.set(false);
          return EMPTY;
        }),
      )
      .subscribe();
  }
}
