import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  OnDestroy,
  OnInit,
} from '@angular/core';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { AuthService } from '../../core/services/auth.service';
import { WebSocketService } from '../../core/services/websocket.service';
import { SupervisorMetricsService } from './services/supervisor-metrics.service';
import { AgentMetric, AgentStatus, QueueMetric } from './models/supervisor-metrics.model';

@Component({
  selector: 'app-supervisor-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [TranslocoModule],
  templateUrl: './supervisor-dashboard.component.html',
  styleUrl: './supervisor-dashboard.component.scss',
})
export class SupervisorDashboardComponent implements OnInit, OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly transloco = inject(TranslocoService);
  protected readonly wsService = inject(WebSocketService);
  protected readonly metricsService = inject(SupervisorMetricsService);
  private readonly el = inject(ElementRef<HTMLElement>);

  /** Configurable threshold (minutes) for break alert highlight */
  readonly breakAlertMinutes = 10;

  protected readonly metrics = this.metricsService.metrics;

  // ─── KPI derived values ────────────────────────────────────────
  protected readonly activeCalls = computed(() => this.metrics()?.kpi.activeCalls ?? 0);
  protected readonly callsInIvr = computed(() => this.metrics()?.kpi.callsInIvr ?? 0);
  protected readonly avgWaitTime = computed(() => this.metrics()?.kpi.avgWaitTime ?? 0);
  protected readonly avgHandleTime = computed(() => this.metrics()?.kpi.avgHandleTime ?? 0);

  protected readonly availableCount = computed(
    () => this.metrics()?.agents.filter((a) => a.status === 'AVAILABLE').length ?? 0,
  );
  protected readonly breakCount = computed(
    () => this.metrics()?.agents.filter((a) => a.status === 'BREAK').length ?? 0,
  );
  protected readonly acwCount = computed(
    () => this.metrics()?.agents.filter((a) => a.status === 'AFTER_CONTACT').length ?? 0,
  );

  protected readonly agents = computed(() => this.metrics()?.agents ?? []);
  protected readonly queues = computed(() => this.metrics()?.queues ?? []);

  /** Max waiting in any queue – used as bar chart denominator */
  protected readonly maxWaiting = computed(() => {
    const qs = this.queues();
    if (!qs.length) return 1;
    return Math.max(1, ...qs.map((q) => q.waiting));
  });

  ngOnInit(): void {
    const tenantId = this.auth.currentTenantId();
    if (!tenantId) return;

    if (this.wsService.connectionState() === 'DISCONNECTED') {
      this.wsService.connect();
    }
    this.metricsService.subscribe(tenantId);
  }

  ngOnDestroy(): void {
    const tenantId = this.auth.currentTenantId();
    if (tenantId) {
      this.metricsService.unsubscribe(tenantId);
    }
    this.wsService.disconnect();
  }

  // ─── Helpers ───────────────────────────────────────────────────

  protected breakMinutes(agent: AgentMetric): number {
    if (agent.status !== 'BREAK' || !agent.breakStartedAt) return 0;
    const started = new Date(agent.breakStartedAt).getTime();
    return Math.max(0, Math.floor((Date.now() - started) / 60_000));
  }

  protected statusLabel(status: AgentStatus): string {
    const keyMap: Record<AgentStatus, string> = {
      AVAILABLE: 'agent.status.available',
      BUSY: 'agent.status.busy',
      BREAK: 'agent.status.break',
      AFTER_CONTACT: 'agent.status.afterContact',
      OFFLINE: 'agent.status.offline',
    };
    return this.transloco.translate(keyMap[status] ?? status);
  }

  protected isBreakAlert(agent: AgentMetric): boolean {
    if (agent.status !== 'BREAK') return false;
    return this.breakMinutes(agent) >= this.breakAlertMinutes;
  }

  protected queueBarWidth(queue: QueueMetric): number {
    return Math.min(100, (queue.waiting / this.maxWaiting()) * 100);
  }

  protected queueBarClass(queue: QueueMetric): string {
    const pct = this.queueBarWidth(queue);
    if (pct >= 90) return 'fill-danger';
    if (pct >= 60) return 'fill-warning';
    return '';
  }

  /** Format seconds → MM:SS */
  protected secondsToTime(secs: number): string {
    const m = Math.floor(secs / 60);
    const s = secs % 60;
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }

  toggleFullscreen(): void {
    if (!document.fullscreenElement) {
      this.el.nativeElement.requestFullscreen();
    } else {
      document.exitFullscreen();
    }
  }
}
