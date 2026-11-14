import { Injectable, computed, inject, signal } from '@angular/core';
import { Subject, Subscription, take } from 'rxjs';
import { HttpClient } from '@angular/common/http';
import { Client, IMessage, StompConfig } from '@stomp/stompjs';
import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';
import { WsEvent } from '../../features/agent/models/ws-event.model';

/**
 * Presence ping (BE-210): `SEND /app/ping` right after every connect and then at this interval.
 * The backend marks an agent OFFLINE after 90 s without one, so this leaves room for two lost
 * pings. STOMP heartbeats (FE-155) are a separate, transport-level setting and stay untouched.
 */
export const PRESENCE_PING_INTERVAL_MS = 30000;
export const PRESENCE_PING_DESTINATION = '/app/ping';
/**
 * Browsers throttle timers of background tabs (Chrome: once a minute, frozen tabs not at all),
 * which can eat the backend grace period. When the tab becomes visible, the network returns or
 * the window gets focus, a ping goes out at once (after this short debounce, so the three events
 * that usually arrive together cost one ping).
 */
export const WAKE_DEBOUNCE_MS = 500;

/** Reconnect backoff (FE-155/FE-169): 1 s, 2 s, 4 s ... capped at 30 s, with equal jitter. */
export const RECONNECT_BASE_DELAY_MS = 1000;
export const RECONNECT_MAX_DELAY_MS = 30000;

/**
 * Delay before reconnect attempt number `attempt` (0-based). Exponential, capped, "equal jitter":
 * half of the ceiling is fixed and the other half random, so a fleet of clients dropped by the same
 * server restart does not come back in lockstep. `random` returns [0, 1) and is injectable.
 */
export function computeReconnectDelay(attempt: number, random: () => number = Math.random): number {
  const ceiling = Math.min(RECONNECT_MAX_DELAY_MS, RECONNECT_BASE_DELAY_MS * 2 ** attempt);
  return Math.round(ceiling / 2 + random() * (ceiling / 2));
}

/**
 * Terminal STOMP ERROR classes (BE-229 / WebSocketAuthInterceptor). The backend sends ERROR with a
 * `message` header and closes the session; these texts are matched, anything else is treated as a
 * transient broker error and retried with backoff.
 *  - FORBIDDEN: "Brak uprawnień do wskazanej destynacji STOMP" (SUBSCRIBE/SEND rejected): a retry
 *    would be rejected again, so reconnecting stops.
 *  - TOKEN: "Brak tokenu..." / "Nieprawidłowy token JWT..." on CONNECT: one refresh-and-retry.
 */
export type WsErrorKind = 'FORBIDDEN' | 'TOKEN' | 'OTHER';

export function classifyStompError(frame: { headers?: Record<string, string> }): WsErrorKind {
  const message = (frame.headers?.['message'] ?? '').toLowerCase();
  if (message.includes('uprawnie')) return 'FORBIDDEN';
  // "claim": JwtParser's "Nieprawidłowy format UUID w claim ..." is a token problem as well.
  // TODO(BE-236): classify by a stable `code` native header of the ERROR frame instead of message
  // substrings (language/wording dependent); keep the substrings only as a fallback.
  if (message.includes('token') || message.includes('claim')) return 'TOKEN';
  return 'OTHER';
}

/**
 * CONNECTING/CONNECTED/DISCONNECTED/ERROR: transport states (the client keeps retrying).
 * FORBIDDEN and SESSION_EXPIRED are terminal: automatic reconnect is off until connect() is
 * called again (new login / role change).
 */
export type WsConnectionState =
  | 'CONNECTING'
  | 'CONNECTED'
  | 'DISCONNECTED'
  | 'ERROR'
  | 'FORBIDDEN'
  | 'SESSION_EXPIRED';

@Injectable({ providedIn: 'root' })
export class WebSocketService {
  private readonly auth = inject(AuthService);
  private readonly http = inject(HttpClient);

  readonly connectionState = signal<WsConnectionState>('DISCONNECTED');

  /** True while resumeAfterRejection() waits for the profile; the banner disables its button. */
  readonly resuming = signal(false);

  /** Terminal rejection: automatic reconnect is off until connect() is called again. */
  readonly isTerminal = computed(() => {
    const state = this.connectionState();
    return state === 'FORBIDDEN' || state === 'SESSION_EXPIRED';
  });

  /** No live link: consumers fall back to polling and show a notice (transient or terminal). */
  readonly isDown = computed(() => {
    const state = this.connectionState();
    return state === 'DISCONNECTED' || state === 'ERROR' || this.isTerminal();
  });

  private readonly _events$ = new Subject<WsEvent>();
  /** Stream of all incoming WebSocket events */
  readonly events$ = this._events$.asObservable();

  private client: Client | null = null;
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  private wakeTimer: ReturnType<typeof setTimeout> | null = null;
  private removeWakeListeners: (() => void) | null = null;
  private readonly _onWakeCallbacks = new Set<() => void>();
  /** Failed attempts since the last successful connect; drives the backoff. */
  private reconnectAttempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  /** Set by a terminal ERROR: the close that follows must not schedule a reconnect. */
  private halted = false;
  /** One token refresh per outage; reset by a successful connect. */
  private tokenRefreshTried = false;
  /** In-flight token refresh of a TOKEN error; cancelled by connect()/disconnect(). */
  private refreshSub: Subscription | null = null;
  /** The wake that arrived is a network/visibility one (not a bare focus): retry a pending backoff. */
  private wakeRetries = false;

  /**
   * Callbacks invoked on every successful STOMP connect (including reconnects).
   * Consumers register via onConnect() and receive an unregister function.
   */
  private readonly _onConnectCallbacks = new Set<() => void>();

  /**
   * Registers a callback that will be called on every successful STOMP connect,
   * including reconnects. Returns an unregister function that removes the callback.
   *
   * Usage:
   *   const unregister = this.ws.onConnect(() => this.doRecovery());
   *   destroyRef.onDestroy(() => unregister());
   */
  onConnect(callback: () => void): () => void {
    this._onConnectCallbacks.add(callback);
    return () => this._onConnectCallbacks.delete(callback);
  }

  /**
   * Registers a callback fired (debounced) when the tab becomes visible, the browser goes back
   * online or the window regains focus. Returns an unregister function.
   */
  onWake(callback: () => void): () => void {
    this._onWakeCallbacks.add(callback);
    return () => this._onWakeCallbacks.delete(callback);
  }

  /**
   * Extra STOMP destinations registered by feature modules (e.g. supervisor topic).
   * These are re-subscribed automatically on every reconnect.
   */
  private readonly _extraTopics = new Set<string>();

  connect(): void {
    if (this.client?.active) return;
    // A previous client may sit in backoff (deactivated, timer pending) or in a terminal state.
    this.clearReconnectTimer();
    this.cancelRefresh();

    const wsUrl = environment.wsUrl;

    this.connectionState.set('CONNECTING');
    this.reconnectAttempt = 0;
    this.halted = false;
    this.tokenRefreshTried = false;

    const config: StompConfig = {
      brokerURL: wsUrl,
      connectHeaders: {},
      heartbeatOutgoing: 20000,
      heartbeatIncoming: 0,
      // stompjs' own loop is off: it has no jitter. Reconnects are scheduled in scheduleReconnect().
      reconnectDelay: 0,
      beforeConnect: (client: Client) => {
        if (this.connectionState() !== 'CONNECTING') this.connectionState.set('CONNECTING');
        // Always read the current token so reconnects use the refreshed one
        client.connectHeaders = {
          Authorization: `Bearer ${this.auth.getAccessToken() ?? ''}`,
        };
      },
      onConnect: () => {
        if (this.client !== client) return;
        this.reconnectAttempt = 0;
        this.tokenRefreshTried = false;
        this.connectionState.set('CONNECTED');
        this.subscribeToUserEvents();
        this.resubscribeExtraTopics();
        this.startPresencePing();
        this._onConnectCallbacks.forEach((cb) => cb());
      },
      onDisconnect: () => {
        if (this.client !== client) return;
        this.stopPresencePing();
        this.connectionState.set('DISCONNECTED');
      },
      onStompError: (frame) => {
        if (this.client !== client) return;
        console.error('[WS] STOMP error:', frame.headers?.['message'] ?? frame);
        this.stopPresencePing();
        this.handleStompError(classifyStompError(frame));
      },
      onWebSocketError: (event) => {
        if (this.client !== client) return;
        console.error('[WS] WebSocket error:', event);
        this.stopPresencePing();
        this.connectionState.set('ERROR');
      },
      onWebSocketClose: () => {
        // A late close of a replaced client (logout -> login) must not touch the new one.
        if (this.client !== client) return;
        this.stopPresencePing();
        if (this.halted) return;
        const state = this.connectionState();
        if (state !== 'DISCONNECTED' && state !== 'ERROR') {
          this.connectionState.set('DISCONNECTED');
        }
        this.scheduleReconnect();
      },
    };

    const client: Client = new Client(config);
    this.client = client;
    client.activate();
    this.listenForWake();
  }

  disconnect(): void {
    this.clearReconnectTimer();
    this.cancelRefresh();
    this.halted = false;
    this.stopPresencePing();
    this.stopListeningForWake();
    if (this.client) {
      this.client.deactivate();
      this.client = null;
    }
    this._extraTopics.clear();
    this.connectionState.set('DISCONNECTED');
  }

  /**
   * "Try again" after a terminal rejection: re-reads the profile (role/permissions may have changed;
   * the HTTP layer handles an expired session) and opens a fresh connection. Registered topics are
   * kept.
   */
  resumeAfterRejection(): void {
    if (this.resuming()) return;
    this.resuming.set(true);
    this.http
      .get(`${environment.apiUrl}/users/me`)
      .pipe(take(1))
      .subscribe({
        next: () => {
          this.resuming.set(false);
          this.connect();
        },
        error: () => {
          this.resuming.set(false);
          // A 401 makes the HTTP layer log the user out: do not open a socket for a dead session.
          if (this.auth.isAuthenticated()) this.connect();
        },
      });
  }

  publish(destination: string, body: unknown): void {
    if (!this.client?.connected) return;
    this.client.publish({
      destination,
      body: JSON.stringify(body),
    });
  }

  /**
   * Registers an additional STOMP topic that funnels messages into the shared
   * events$ stream. The topic is stored and automatically re-subscribed after
   * every reconnect, so callers do not need to re-register on reconnect.
   *
   * Call this before or after connect() – if the client is already connected
   * the subscription is activated immediately; otherwise it will be activated
   * on the next onConnect callback.
   *
   * To clean up, call unregisterTopic() when the consuming component is destroyed.
   */
  registerTopic(destination: string): void {
    this._extraTopics.add(destination);
    if (this.client?.connected) {
      this.subscribeOneTopic(destination);
    }
  }

  /**
   * Removes a previously registered extra topic. The STOMP subscription that
   * was created by registerTopic() will remain active until the next disconnect;
   * after that the topic will not be re-subscribed.
   */
  unregisterTopic(destination: string): void {
    this._extraTopics.delete(destination);
  }

  /**
   * Terminal errors stop the reconnect loop (the server rejected the session for a reason a retry
   * cannot fix); a token error gets one refresh-and-retry; anything else is a plain drop.
   */
  private handleStompError(kind: WsErrorKind): void {
    if (kind === 'FORBIDDEN') {
      this.halt('FORBIDDEN');
      return;
    }
    if (kind === 'TOKEN') {
      if (this.tokenRefreshTried) {
        this.halt('SESSION_EXPIRED');
        return;
      }
      this.tokenRefreshTried = true;
      this.halted = true; // the close that follows must not schedule a plain retry
      this.connectionState.set('DISCONNECTED'); // the link is dead while the refresh runs
      this.cancelRefresh();
      // Shared single-flight refresh (AuthService): no second POST /auth/refresh next to the HTTP
      // interceptor's. Cancelled by connect()/disconnect(), so a late answer never touches a new session.
      this.refreshSub = this.auth
        .refresh()
        .pipe(take(1))
        .subscribe({
          next: () => {
            this.refreshSub = null;
            this.halted = false;
            this.scheduleReconnect(true);
          },
          error: () => {
            this.refreshSub = null;
            this.halt('SESSION_EXPIRED');
          },
        });
      return;
    }
    this.connectionState.set('ERROR');
    // The server usually closes the session after ERROR, but nothing guarantees it: do not wait.
    this.scheduleReconnect();
  }

  private halt(state: 'FORBIDDEN' | 'SESSION_EXPIRED'): void {
    this.halted = true;
    this.clearReconnectTimer();
    this.connectionState.set(state);
    void this.client?.deactivate();
  }

  /**
   * One timer, one delay function: deactivate the dead client (stompjs would otherwise sit active
   * without a loop) and activate it again after backoff with jitter. `immediate` skips the delay
   * (token just refreshed).
   */
  private scheduleReconnect(immediate = false): void {
    const client = this.client;
    if (!client || this.reconnectTimer !== null) return;
    const delay = immediate ? 0 : computeReconnectDelay(this.reconnectAttempt);
    this.reconnectAttempt++;
    const deactivated = client.deactivate();
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      if (this.client !== client || this.halted) return;
      void deactivated.then(() => {
        if (this.client === client && !this.halted) client.activate();
      });
    }, delay);
  }

  private cancelRefresh(): void {
    this.refreshSub?.unsubscribe();
    this.refreshSub = null;
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  /** Pings now and then every interval; a restart never leaves two timers running. */
  private startPresencePing(): void {
    this.stopPresencePing();
    this.publish(PRESENCE_PING_DESTINATION, {});
    this.pingTimer = setInterval(
      () => this.publish(PRESENCE_PING_DESTINATION, {}),
      PRESENCE_PING_INTERVAL_MS,
    );
  }

  private listenForWake(): void {
    this.stopListeningForWake();
    const onWake = (retry = false) => {
      this.wakeRetries ||= retry;
      if (this.wakeTimer !== null) return;
      this.wakeTimer = setTimeout(() => {
        this.wakeTimer = null;
        const retryNow = this.wakeRetries;
        this.wakeRetries = false;
        // Network back / tab visible during a backoff wait: do not make the agent wait up to 30 s.
        // Terminal states are never retried from here.
        if (retryNow && this.reconnectTimer !== null && !this.halted && !this.isTerminal()) {
          this.clearReconnectTimer();
          this.reconnectAttempt = 0;
          this.scheduleReconnect(true);
        }
        this.publish(PRESENCE_PING_DESTINATION, {});
        this._onWakeCallbacks.forEach((cb) => cb());
      }, WAKE_DEBOUNCE_MS);
    };
    const onVisibility = () => {
      if (document.visibilityState === 'visible') onWake(true);
    };
    const onOnline = () => onWake(true);
    const onFocus = () => onWake();
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('online', onOnline);
    window.addEventListener('focus', onFocus);
    this.removeWakeListeners = () => {
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('online', onOnline);
      window.removeEventListener('focus', onFocus);
    };
  }

  private stopListeningForWake(): void {
    this.removeWakeListeners?.();
    this.removeWakeListeners = null;
    if (this.wakeTimer !== null) {
      clearTimeout(this.wakeTimer);
      this.wakeTimer = null;
    }
  }

  private stopPresencePing(): void {
    if (this.pingTimer !== null) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
  }

  private subscribeOneTopic(destination: string): void {
    if (!this.client?.connected) return;
    this.client.subscribe(destination, (message: IMessage) => {
      try {
        const event = JSON.parse(message.body) as WsEvent;
        this._events$.next(event);
      } catch {
        // ignore unparseable frames
      }
    });
  }

  private resubscribeExtraTopics(): void {
    for (const dest of this._extraTopics) {
      this.subscribeOneTopic(dest);
    }
  }

  private subscribeToUserEvents(): void {
    if (!this.client?.connected) return;

    const onMessage = (message: IMessage) => {
      try {
        const event = JSON.parse(message.body) as WsEvent;
        this._events$.next(event);
      } catch {
        // ignore unparseable
      }
    };

    // Unicast: events targeted at this specific user
    const userId = this.auth.currentUserId();
    if (userId) {
      this.client.subscribe(`/topic/user/${userId}/events`, onMessage);
    }

    // Broadcast: events sent to all agents of this tenant
    const tenantId = this.auth.currentTenantId();
    if (tenantId) {
      this.client.subscribe(`/topic/tenant/${tenantId}/agents`, onMessage);
    }
  }
}
