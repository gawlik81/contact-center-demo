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
