import { Injectable, inject, signal } from '@angular/core';
import { HttpClient, HttpContext } from '@angular/common/http';
import { catchError, EMPTY, filter, Observable, Subscription, switchMap, tap } from 'rxjs';
import { TranslocoService } from '@jsverse/transloco';
import { environment } from '../../../../environments/environment';
import { AgentStatus } from '../models/agent-status.model';
import { NotificationService } from '../../../core/services/notification.service';
import { WebSocketService } from '../../../core/services/websocket.service';
import { SKIP_ERROR_TOAST } from '../../../core/interceptors/error-handler.interceptor';
import { AgentStatusChangedPayload, WsEvent } from '../models/ws-event.model';

interface UserResponse {
  id: string;
  email: string;
  firstName: string;
  lastName: string;
  role: string;
  status: string;
  /** ISO-8601 UTC start of the current status; null/absent = unknown (old backend). */
  statusSince?: string | null;
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
   * Epoch ms of when the current status began. Source of truth is the server value
   * (`GET /users/me`, `AGENT_STATUS_CHANGED`), so the elapsed time survives a page reload. When the
   * server sends null/nothing (rolling deploy: new FE, old BE) the client clock at the moment the
   * change was applied is used instead.
   */
  readonly statusSince = signal<number | null>(null);

  /**
   * Counter that moves exactly when a NEW status episode starts: a different status, or the same
   * status with a different server `statusSince` (e.g. BREAK -> AVAILABLE -> BREAK in another tab).
   * It does not move on a re-sync that returns the same values, nor when a server timestamp merely
   * refines a client-side fallback of the same episode. Use it (not `statusSince`) as an
   * "episode changed" marker.
   */
  readonly statusEpisode = signal(0);

  /** True when `statusSince` came from the server rather than the client clock fallback. */
  private sinceFromServer = false;

  /**
   * Moves on every status change the client learns about from a newer source than a GET in flight
   * (our own PATCH, a WS event). A `/users/me` answer whose request predates the latest move is
   * stale and ignored, also while the origin is still a client-clock fallback.
   */
  private statusSeq = 0;

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
            this.statusSeq++;
            this.applyStatus(payload.status, payload.statusSince);
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
    // Root singleton: the next login in this tab must not inherit the previous agent's status.
    this.currentStatus.set('OFFLINE');
    this.statusSince.set(null);
    this.statusEpisode.set(0);
    this.sinceFromServer = false;
    this.statusSeq = 0;
  }

  /**
   * Applies a status and its start. A different status starts a new episode (origin = server
   * value, else client clock). For the same status only a different server value matters: if the
   * current origin was a client fallback it is refined silently (same episode, e.g. the WS echo of
   * our own change); if it was already a server value, the server says a new episode began.
   */
  private applyStatus(status: AgentStatus, serverSince?: string | null): void {
    const parsed = parseServerInstant(serverSince);
    const current = this.statusSince();
    // A late answer (e.g. /users/me sent before a newer WS event) must not roll the status or the
    // clock back: with two server timestamps the older one is simply stale.
    if (this.sinceFromServer && parsed !== null && current !== null && parsed < current) return;
    if (status !== this.currentStatus() || current === null) {
      this.currentStatus.set(status);
      this.statusSince.set(parsed ?? Date.now());
      this.sinceFromServer = parsed !== null;
      this.statusEpisode.update((n) => n + 1);
      return;
    }
    if (parsed === null || parsed === current) return;
    this.statusSince.set(parsed);
    if (this.sinceFromServer) {
      this.statusEpisode.update((n) => n + 1);
    }
    this.sinceFromServer = true;
  }

  private fetchAndSyncStatus(): void {
    const requestSeq = this.statusSeq;
    const sub = this.http
      .get<UserResponse>(`${environment.apiUrl}/users/me`)
      .pipe(
        switchMap((user) => {
          this.currentUserId = user.id;
          // A newer local change or WS event overtook this request: its answer is stale.
          if (requestSeq !== this.statusSeq) return EMPTY;
          const status = user?.status as AgentStatus | undefined;
          if (status && ACTIVE_STATUSES.has(status)) {
            this.applyStatus(status, user.statusSince);
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
   * `silent` suppresses the error toasts (for changes the agent did not ask for).
   */
  changeStatus(status: AgentStatus, options: { silent?: boolean } = {}): Observable<void> {
    if (this.isChanging()) {
      return EMPTY;
    }
    this.isChanging.set(true);

    return this.http
      .patch<void>(
        `${environment.apiUrl}/users/me/status`,
        { status },
        options.silent ? { context: new HttpContext().set(SKIP_ERROR_TOAST, true) } : {},
      )
      .pipe(
        tap(() => {
          this.statusSeq++;
          this.applyStatus(status);
          // Notify backend via WebSocket so supervisors get real-time update
          this.ws.publish('/app/agent/status', { status });
          this.isChanging.set(false);
        }),
        catchError(() => {
          // silent: a change the agent did not ask for (recovery) must not raise an error toast
          if (!options.silent) {
            this.notifications.error(this.transloco.translate('agent.status.errorChange'));
          }
          this.isChanging.set(false);
          return EMPTY;
        }),
      );
  }
}

/** Epoch ms of an ISO-8601 instant, or null for absent/unparseable input. */
function parseServerInstant(value: string | null | undefined): number | null {
  if (!value) return null;
  const ms = Date.parse(value);
  return Number.isNaN(ms) ? null : ms;
}
