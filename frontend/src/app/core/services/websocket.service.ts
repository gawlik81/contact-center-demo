import { Injectable, inject, signal } from '@angular/core';
import { Subject } from 'rxjs';
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

export type WsConnectionState = 'CONNECTING' | 'CONNECTED' | 'DISCONNECTED' | 'ERROR';

@Injectable({ providedIn: 'root' })
export class WebSocketService {
  private readonly auth = inject(AuthService);

  readonly connectionState = signal<WsConnectionState>('DISCONNECTED');

  private readonly _events$ = new Subject<WsEvent>();
  /** Stream of all incoming WebSocket events */
  readonly events$ = this._events$.asObservable();

  private client: Client | null = null;
  private pingTimer: ReturnType<typeof setInterval> | null = null;
  private wakeTimer: ReturnType<typeof setTimeout> | null = null;
  private removeWakeListeners: (() => void) | null = null;
  private readonly _onWakeCallbacks = new Set<() => void>();

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

    const wsUrl = environment.wsUrl;

    this.connectionState.set('CONNECTING');

    const config: StompConfig = {
      brokerURL: wsUrl,
      connectHeaders: {},
      heartbeatOutgoing: 20000,
      heartbeatIncoming: 0,
      reconnectDelay: 1000,
      beforeConnect: (client: Client) => {
        // Always read the current token so reconnects use the refreshed one
        client.connectHeaders = {
          Authorization: `Bearer ${this.auth.getAccessToken() ?? ''}`,
        };
      },
      onConnect: () => {
        this.connectionState.set('CONNECTED');
        this.subscribeToUserEvents();
        this.resubscribeExtraTopics();
        this.startPresencePing();
        this._onConnectCallbacks.forEach((cb) => cb());
      },
      onDisconnect: () => {
        this.stopPresencePing();
        this.connectionState.set('DISCONNECTED');
      },
      onStompError: (frame) => {
        console.error('[WS] STOMP error:', frame);
        this.stopPresencePing();
        this.connectionState.set('ERROR');
      },
      onWebSocketError: (event) => {
        console.error('[WS] WebSocket error:', event);
        this.stopPresencePing();
        this.connectionState.set('ERROR');
      },
      onWebSocketClose: () => {
        this.stopPresencePing();
        if (this.connectionState() !== 'DISCONNECTED') {
          this.connectionState.set('DISCONNECTED');
        }
      },
    };

    this.client = new Client(config);
    this.client.activate();
    this.listenForWake();
  }

  disconnect(): void {
    this.stopPresencePing();
    this.stopListeningForWake();
    if (this.client) {
      this.client.deactivate();
      this.client = null;
    }
    this._extraTopics.clear();
    this.connectionState.set('DISCONNECTED');
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
    const onWake = () => {
      if (this.wakeTimer !== null) return;
      this.wakeTimer = setTimeout(() => {
        this.wakeTimer = null;
        this.publish(PRESENCE_PING_DESTINATION, {});
        this._onWakeCallbacks.forEach((cb) => cb());
      }, WAKE_DEBOUNCE_MS);
    };
    const onVisibility = () => {
      if (document.visibilityState === 'visible') onWake();
    };
    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('online', onWake);
    window.addEventListener('focus', onWake);
    this.removeWakeListeners = () => {
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('online', onWake);
      window.removeEventListener('focus', onWake);
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
