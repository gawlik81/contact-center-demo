import { Injectable, inject, signal } from '@angular/core';
import { Subject } from 'rxjs';
import { Client, IMessage, StompConfig } from '@stomp/stompjs';
import { environment } from '../../../environments/environment';
import { AuthService } from './auth.service';
import { WsEvent } from '../../features/agent/models/ws-event.model';

export type WsConnectionState = 'CONNECTING' | 'CONNECTED' | 'DISCONNECTED' | 'ERROR';

@Injectable({ providedIn: 'root' })
export class WebSocketService {
  private readonly auth = inject(AuthService);

  readonly connectionState = signal<WsConnectionState>('DISCONNECTED');

  private readonly _events$ = new Subject<WsEvent>();
  /** Stream of all incoming WebSocket events */
  readonly events$ = this._events$.asObservable();

  private client: Client | null = null;

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
   * Extra STOMP destinations registered by feature modules (e.g. supervisor topic).
   * These are re-subscribed automatically on every reconnect.
   */
  private readonly _extraTopics = new Set<string>();

  connect(): void {
    if (this.client?.active) return;

    const token = this.auth.getAccessToken();
    const wsUrl = environment.wsUrl;

    this.connectionState.set('CONNECTING');

    const config: StompConfig = {
      brokerURL: wsUrl,
      connectHeaders: {
        Authorization: `Bearer ${token ?? ''}`,
      },
      heartbeatOutgoing: 20000,
      heartbeatIncoming: 0,
      reconnectDelay: 1000,
      onConnect: () => {
        this.connectionState.set('CONNECTED');
        this.subscribeToUserEvents();
        this.resubscribeExtraTopics();
        this._onConnectCallbacks.forEach((cb) => cb());
      },
      onDisconnect: () => {
        this.connectionState.set('DISCONNECTED');
      },
      onStompError: (frame) => {
        console.error('[WS] STOMP error:', frame);
        this.connectionState.set('ERROR');
      },
      onWebSocketError: (event) => {
        console.error('[WS] WebSocket error:', event);
        this.connectionState.set('ERROR');
      },
      onWebSocketClose: () => {
        if (this.connectionState() !== 'DISCONNECTED') {
          this.connectionState.set('DISCONNECTED');
        }
      },
    };

    this.client = new Client(config);
    this.client.activate();
  }

  disconnect(): void {
    if (this.client) {
      this.client.deactivate();
      this.client = null;
    }
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
