import { Injectable, inject, signal } from '@angular/core';
import { filter } from 'rxjs';
import { WebSocketService } from '../../../core/services/websocket.service';
import { QueueItem } from '../models/queue-item.model';
import { WsEvent, QueueUpdatePayload } from '../models/ws-event.model';

@Injectable({ providedIn: 'root' })
export class QueueStateService {
  private readonly ws = inject(WebSocketService);

  readonly queueItems = signal<QueueItem[]>([]);

  constructor() {
    this.ws.events$
      .pipe(filter((e: WsEvent) => e.eventType === 'QUEUE_UPDATE'))
      .subscribe((e) => {
        const payload = e.payload as QueueUpdatePayload;
        this.queueItems.set(payload.items ?? []);
      });
  }
}
