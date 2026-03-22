import { Injectable, inject, signal } from '@angular/core';
import { filter } from 'rxjs/operators';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { WebSocketService } from '../../../core/services/websocket.service';
import {
  SupervisorMetrics,
  SupervisorMetricsRawPayload,
} from '../models/supervisor-metrics.model';

@Injectable({ providedIn: 'root' })
export class SupervisorMetricsService {
  private readonly wsService = inject(WebSocketService);

  private readonly _metrics = signal<SupervisorMetrics | null>(null);
  readonly metrics = this._metrics.asReadonly();

  constructor() {
    this.wsService.events$
      .pipe(
        filter((e) => e.eventType === 'SUPERVISOR_METRICS'),
        takeUntilDestroyed(),
      )
      .subscribe((e) => {
        const raw = e.payload as SupervisorMetricsRawPayload;
        this._metrics.set(this.mapPayload(raw));
      });
  }

  subscribe(tenantId: string): void {
    this.wsService.registerTopic(`/topic/tenant/${tenantId}/supervisor`);
  }

  unsubscribe(tenantId: string): void {
    this.wsService.unregisterTopic(`/topic/tenant/${tenantId}/supervisor`);
  }

  private mapPayload(raw: SupervisorMetricsRawPayload): SupervisorMetrics {
    return {
      agents: raw.agents.map((a) => ({
        id: a.id,
        name: a.name,
        status: a.status,
        currentContact: a.current_contact,
        breakStartedAt: a.break_started_at ?? null,
      })),
      queues: raw.queues.map((q) => ({
        id: q.id,
        name: q.name,
        waiting: q.waiting,
        availableAgents: q.available_agents,
      })),
      kpi: {
        activeCalls: raw.kpi.active_calls,
        avgWaitTime: raw.kpi.avg_wait_time,
        avgHandleTime: raw.kpi.avg_handle_time,
      },
    };
  }
}
