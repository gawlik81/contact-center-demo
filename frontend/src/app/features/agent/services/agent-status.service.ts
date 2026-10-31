import { Injectable, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { catchError, EMPTY, filter, Observable, Subscription, switchMap, tap } from 'rxjs';
import { TranslocoService } from '@jsverse/transloco';
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
  private readonly transloco = inject(TranslocoService);
  private readonly ws = inject(WebSocketService);

  readonly currentStatus = signal<AgentStatus>('OFFLINE');
  readonly isChanging = signal(false);

  /**
   * Epoch ms of when the current status began, as observed by THIS client. The API
   * (`GET /users/me`, `AGENT_STATUS_CHANGED`) carries no status start time, so the timestamp is
   * taken locally when a status change is applied; after a page reload mid-break the elapsed time
   * restarts from the reload. Replace with the server value once the backend exposes it.
   */
  readonly statusSince = signal<number | null>(null);

  private currentUserId: string | null = null;
  private initialized = false;
  private unregisterConnect: (() => void) | null = null;
  private subscriptions = new Subscription();

  /**
   * Fetches the current user profile via GET /api/users/me and initialises the agent status.
   * Also subscribes to AGENT_STATUS_CHANGED WebSocket events so server-side status changes
   * (e.g. automatic break activation by the scheduler) are reflected immediately in the UI.
   * On every WS reconnect the status is re-synced from the server to recover any events
   * missed during a temporary disconnect.
   */
  initStatus(): void {
    // Idempotent: a second call while initialised must not stack another WS handler.
    if (this.initialized) return;
    this.initialized = true;

    this.applyStatus('OFFLINE');
    this.fetchAndSyncStatus();

    this.unregisterConnect = this.ws.onConnect(() => this.fetchAndSyncStatus());

    this.subscriptions.add(
      this.ws.events$
        .pipe(filter((e: WsEvent) => e.eventType === 'AGENT_STATUS_CHANGED'))
        .subscribe((e) => {
          const payload = e.payload as AgentStatusChangedPayload;
          if (payload.agentId === this.currentUserId) {
            this.applyStatus(payload.status);
          }
        }),
    );
  }

  /**
   * Detaches everything `initStatus()` registered (WS connect callback, event stream, in-flight
   * sync) and forgets the user, so a new login in the same tab starts clean. Called when the
   * agent shell is destroyed (logout).
   */
  stopStatus(): void {
    this.unregisterConnect?.();
    this.unregisterConnect = null;
    this.subscriptions.unsubscribe();
    this.subscriptions = new Subscription();
    this.currentUserId = null;
    this.initialized = false;
    this.isChanging.set(false);
  }

  /** Sets the status and restarts the elapsed-time origin only when the status really changes. */
  private applyStatus(status: AgentStatus): void {
    if (status === this.currentStatus() && this.statusSince() !== null) return;
    this.currentStatus.set(status);
    this.statusSince.set(Date.now());
  }

  private fetchAndSyncStatus(): void {
    const sub = this.http
      .get<UserResponse>(`${environment.apiUrl}/users/me`)
      .pipe(
        switchMap((user) => {
          this.currentUserId = user.id;
          const status = user?.status as AgentStatus | undefined;
          if (status && ACTIVE_STATUSES.has(status)) {
            this.applyStatus(status);
            return EMPTY;
          }
          return this.changeStatus('AVAILABLE');
        }),
        catchError(() => {
          this.notifications.error(this.transloco.translate('agent.status.errorLoad'));
          return EMPTY;
        }),
      )
      .subscribe();
    this.subscriptions.add(sub);
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
        this.applyStatus(status);
        // Notify backend via WebSocket so supervisors get real-time update
        this.ws.publish('/app/agent/status', { status });
        this.isChanging.set(false);
      }),
      catchError(() => {
        this.notifications.error(this.transloco.translate('agent.status.errorChange'));
        this.isChanging.set(false);
        return EMPTY;
      }),
    );
  }
}
