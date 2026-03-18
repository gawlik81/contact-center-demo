/**
 * WebSocket Service – native WebSocket API implementation.
 *
 * NOTE: This service uses the native WebSocket API because @stomp/stompjs and
 * sockjs-client are NOT present in package.json. The backend endpoint /ws uses
 * SockJS + STOMP (BE-012). To enable full STOMP support, install the libraries:
 *   npm install @stomp/stompjs sockjs-client
 *   npm install --save-dev @types/sockjs-client
 * Then replace the native WebSocket implementation below with RxStomp from
 * @stomp/rx-stomp (see https://stomp-js.github.io/guide/rx-stomp/).
 *
 * Current implementation: native WebSocket connecting directly to /ws with a
 * custom JSON-based envelope. Reconnect uses exponential backoff capped at 30s.
 */
import { Injectable, inject, signal } from '@angular/core';
import { Subject } from 'rxjs';
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

  private socket: WebSocket | null = null;
  private reconnectAttempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private intentionalDisconnect = false;

  private readonly MIN_RECONNECT_DELAY_MS = 1_000;
  private readonly MAX_RECONNECT_DELAY_MS = 30_000;
  private readonly PING_INTERVAL_MS = 30_000;
  private pingTimer: ReturnType<typeof setInterval> | null = null;

  connect(): void {
    if (this.socket && this.socket.readyState === WebSocket.OPEN) {
      return;
    }
    this.intentionalDisconnect = false;
    this.doConnect();
  }

  disconnect(): void {
    this.intentionalDisconnect = true;
    this.clearReconnectTimer();
    this.clearPingTimer();
    if (this.socket) {
      this.socket.close(1000, 'Client disconnect');
      this.socket = null;
    }
    this.connectionState.set('DISCONNECTED');
  }

  publish(destination: string, body: unknown): void {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      return;
    }
    this.socket.send(JSON.stringify({ destination, body }));
  }

  private doConnect(): void {
    this.connectionState.set('CONNECTING');

    const token = this.auth.getAccessToken();
    // Pass token as query param since native WS cannot set custom headers
    const url = `${environment.wsUrl}?token=${token ?? ''}`;

    try {
      this.socket = new WebSocket(url);
    } catch {
      this.connectionState.set('ERROR');
      this.scheduleReconnect();
      return;
    }

    this.socket.onopen = () => {
      this.connectionState.set('CONNECTED');
      this.reconnectAttempt = 0;
      this.startPingTimer();
    };

    this.socket.onmessage = (event: MessageEvent) => {
      try {
        const parsed = JSON.parse(event.data as string) as WsEvent;
        this._events$.next(parsed);
      } catch {
        // Ignore unparseable messages
      }
    };

    this.socket.onerror = () => {
      this.connectionState.set('ERROR');
    };

    this.socket.onclose = () => {
      this.clearPingTimer();
      if (!this.intentionalDisconnect) {
        this.connectionState.set('DISCONNECTED');
        this.scheduleReconnect();
      }
    };
  }

  private scheduleReconnect(): void {
    this.clearReconnectTimer();
    const delay = Math.min(
      this.MIN_RECONNECT_DELAY_MS * Math.pow(2, this.reconnectAttempt),
      this.MAX_RECONNECT_DELAY_MS,
    );
    this.reconnectAttempt++;
    this.reconnectTimer = setTimeout(() => {
      if (!this.intentionalDisconnect) {
        this.doConnect();
      }
    }, delay);
  }

  private clearReconnectTimer(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
  }

  private startPingTimer(): void {
    this.clearPingTimer();
    this.pingTimer = setInterval(() => {
      this.publish('/app/ping', {});
    }, this.PING_INTERVAL_MS);
  }

  private clearPingTimer(): void {
    if (this.pingTimer !== null) {
      clearInterval(this.pingTimer);
      this.pingTimer = null;
    }
  }
}
