import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe } from '@angular/common';
import { catchError, EMPTY, finalize, merge, Subject, switchMap, tap, timer } from 'rxjs';
import { AdminMetricsService } from '../../services/admin-metrics.service';
import { NotificationService } from '../../../../core/services/notification.service';
import {
  ContactChannelMatrix,
  EtlTableStatus,
  GlobalMetrics,
  GrowthMetrics,
  QueueDepth,
  SystemResourceMetrics,
  TenantChannelRow,
  UsageMetrics,
} from '../../models/admin-metrics.model';

/**
 * Shared poll interval for every auto-refreshing section on this page (overview,
 * usage, growth, ETL, resources). Backend responses are cached (Redis, 5–20 min
 * TTL depending on section) so polling every section this often is cheap — we
 * deliberately use ONE interval for all sections, favouring consistency over
 * per-section cadence tuning.
 */
const METRICS_POLL_INTERVAL_MS = 30_000;
const ETL_LAG_WARNING_THRESHOLD_MINUTES = 30;

type SortableField =
  | 'agentsOnline'
  | 'contactsToday'
  | 'avgHandleTimeSeconds'
  | 'fcrPercentage'
  | 'agentLimitUtilizationPercent';

type SortDir = 'asc' | 'desc';
type RangeOption = '7' | '30' | '90';
type UtilizationTier = 'low' | 'mid' | 'high';
type EtlVisualStatus = 'idle' | 'running' | 'done' | 'warning' | 'error';

/**
 * Maps the cosmetic day-range selector to the `weeks` param accepted by /growth.
 * Note: the contacts-by-channel matrix does NOT need an analogous map — its `days`
 * param equals the selector's value directly (`Number(selectedRange())`), since
 * RangeOption's values ('7'|'30'|'90') already ARE day counts.
 */
const RANGE_TO_WEEKS: Record<RangeOption, number> = {
  '7': 1,
  '30': 4,
  '90': 13,
};

/**
 * Maps a raw backend channel key (as returned in ContactChannelMatrix.channels) to its
 * i18n label key. PHONE/EMAIL labels reuse the same wording already used elsewhere in the
 * app (e.g. supervisor.contactsReport.channelPhone/channelEmail); the SOCIAL_* platforms are
 * new to this matrix (the rest of the app only distinguishes a generic 'SOCIAL' channel), so
 * they get dedicated admin.metrics.channel* keys.
 */
const CHANNEL_LABEL_KEYS: Record<string, string> = {
  PHONE: 'admin.metrics.channelPhone',
  EMAIL: 'admin.metrics.channelEmail',
  SOCIAL_FACEBOOK: 'admin.metrics.channelFacebook',
  SOCIAL_INSTAGRAM: 'admin.metrics.channelInstagram',
  SOCIAL_WHATSAPP: 'admin.metrics.channelWhatsapp',
};

@Component({
  selector: 'cc-admin-metrics-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, DatePipe, DecimalPipe],
  templateUrl: './admin-metrics-page.component.html',
  styleUrl: './admin-metrics-page.component.scss',
})
export class AdminMetricsPageComponent implements OnInit {
  private readonly metricsService = inject(AdminMetricsService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  /** Fired by the "Refresh" button — forces an immediate tick for overview/usage/channelMatrix/growth/etl. */
  private readonly manualRefresh$ = new Subject<void>();
  /**
   * Fired by onRangeChange() — forces an immediate re-fetch of the two sections that depend on
   * the day-range selector: growth (weeks) and the contacts-by-channel matrix (days).
   */
  private readonly rangeChange$ = new Subject<void>();

  // ── Overview + tenant ranking (GET /api/admin/metrics, polled every 30s) ──────
  readonly overviewLoading = signal(true);
  readonly overviewError = signal(false);
  readonly overview = signal<GlobalMetrics | null>(null);

  // ── Usage KPIs (GET /api/admin/metrics/usage, polled every 30s) ───────────────
  readonly usageLoading = signal(true);
  readonly usageError = signal(false);
  readonly usage = signal<UsageMetrics | null>(null);

  // ── Contacts by tenant/channel matrix (GET /api/admin/metrics/contacts-by-channel,
  //    lives in the "Growth & adoption" section — it now covers the same selected
  //    day range as the growth chart rather than "today", polled every 30s) ─────
  readonly channelMatrixLoading = signal(true);
  readonly channelMatrixError = signal(false);
  readonly channelMatrix = signal<ContactChannelMatrix | null>(null);

  // ── Growth + plugin adoption (GET /api/admin/metrics/growth, polled every 30s) ─
  readonly growthLoading = signal(true);
  readonly growthError = signal(false);
  readonly growth = signal<GrowthMetrics | null>(null);
  readonly selectedRange = signal<RangeOption>('30');

  // ── System resources (GET /api/admin/metrics/resources, polled every 30s) ─────
  readonly resourcesLoading = signal(true);
  readonly resourcesError = signal(false);
  readonly resources = signal<SystemResourceMetrics | null>(null);

  // ── ETL pipeline status (GET /api/admin/etl/status, polled every 30s) ─────────
  readonly etlLoading = signal(true);
  readonly etlError = signal(false);
  readonly etlStatuses = signal<EtlTableStatus[]>([]);

  readonly exportingCsv = signal(false);

  // ── Tenant ranking table sorting ───────────────────────────────────────────────
  readonly sortField = signal<SortableField | null>(null);
  readonly sortDir = signal<SortDir>('desc');

  readonly hasAlerts = computed(() => (this.overview()?.systemAlerts?.length ?? 0) > 0);
  readonly tenants = computed(() => this.overview()?.tenants ?? []);
  readonly hasTenants = computed(() => this.tenants().length > 0);

  readonly sortedTenants = computed(() => {
    const list = this.tenants();
    const field = this.sortField();
    if (!field) return list;
    const dir = this.sortDir() === 'asc' ? 1 : -1;
    return [...list].sort((a, b) => (a[field] - b[field]) * dir);
  });

  readonly growthPoints = computed(() => this.growth()?.weeklyPoints ?? []);
  readonly hasGrowthPoints = computed(() => this.growthPoints().length > 0);
  readonly topPlugins = computed(() => this.growth()?.topPlugins ?? []);
  readonly hasTopPlugins = computed(() => this.topPlugins().length > 0);

  readonly growthChartMax = computed(() => {
    const max = this.growthPoints().reduce((acc, p) => Math.max(acc, p.newTenants, p.newUsers), 0);
    return max || 1;
  });

  readonly topPluginMax = computed(() => {
    const max = this.topPlugins().reduce((acc, p) => Math.max(acc, p.installCount), 0);
    return max || 1;
  });

  readonly hasQueueDepths = computed(() => (this.resources()?.queueDepths?.length ?? 0) > 0);
  readonly hasEtlStatuses = computed(() => this.etlStatuses().length > 0);

  readonly channelMatrixChannels = computed(() => this.channelMatrix()?.channels ?? []);
  readonly channelMatrixTenants = computed(() => this.channelMatrix()?.tenants ?? []);
  readonly hasChannelMatrixTenants = computed(() => this.channelMatrixTenants().length > 0);

  /**
   * Drives the header "Refresh" button's spinner/disabled state.
   * True only until each of the five sections completes its FIRST fetch —
   * none of the polling pipelines below ever flip these flags back to `true`
   * on later ticks (auto or manual), so the button stays enabled and the
   * sections never flash their skeleton again after the initial load.
   */
  readonly isRefreshing = computed(
    () =>
      this.overviewLoading() ||
      this.usageLoading() ||
      this.channelMatrixLoading() ||
      this.growthLoading() ||
      this.etlLoading(),
  );

  ngOnInit(): void {
    this.startOverviewPolling();
    this.startUsagePolling();
    this.startChannelMatrixPolling();
    this.startGrowthPolling();
    this.startEtlPolling();
    this.startResourcesPolling();
  }

  // ── Polling ────────────────────────────────────────────────────────────────────

  /** Overview + tenant ranking poll every 30s, plus an immediate tick from onRefresh(). */
  private startOverviewPolling(): void {
    merge(timer(0, METRICS_POLL_INTERVAL_MS), this.manualRefresh$)
      .pipe(
        switchMap(() =>
          this.metricsService.getGlobalMetricsSnapshot().pipe(
            tap(() => this.overviewError.set(false)),
            catchError(() => {
              this.overviewError.set(true);
              this.overviewLoading.set(false);
              return EMPTY;
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((data) => {
        this.overview.set(data);
        this.overviewLoading.set(false);
      });
  }

  /** Usage KPIs poll every 30s, plus an immediate tick from onRefresh(). */
  private startUsagePolling(): void {
    merge(timer(0, METRICS_POLL_INTERVAL_MS), this.manualRefresh$)
      .pipe(
        switchMap(() =>
          this.metricsService.getUsageMetrics().pipe(
            tap(() => this.usageError.set(false)),
            catchError(() => {
              this.usageError.set(true);
              this.usageLoading.set(false);
              return EMPTY;
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((data) => {
        this.usage.set(data);
        this.usageLoading.set(false);
      });
  }

  /**
   * Contacts-by-tenant/channel matrix polls every 30s, always using the CURRENT
   * `selectedRange` value converted to a day count — read fresh inside the switchMap
   * factory on every tick, never captured/frozen at subscribe time (mirrors
   * startGrowthPolling() below exactly). Three trigger sources (the 30s timer, the
   * "Refresh" button via manualRefresh$, and the range selector via rangeChange$) are
   * merged into ONE stream feeding a single switchMap, so there is only ever one
   * in-flight request — changing the range mid-poll cancels any pending fetch instead
   * of racing it.
   */
  private startChannelMatrixPolling(): void {
    merge(timer(0, METRICS_POLL_INTERVAL_MS), this.manualRefresh$, this.rangeChange$)
      .pipe(
        switchMap(() =>
          this.metricsService.getContactChannelMatrix(Number(this.selectedRange())).pipe(
            tap(() => this.channelMatrixError.set(false)),
            catchError(() => {
              this.channelMatrixError.set(true);
              this.channelMatrixLoading.set(false);
              return EMPTY;
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((data) => {
        this.channelMatrix.set(data);
        this.channelMatrixLoading.set(false);
      });
  }

  /**
   * Growth + plugin adoption poll every 30s, always using the CURRENT
   * `selectedRange` value — it's read fresh inside the switchMap factory on
   * every tick, never captured/frozen at subscribe time. Three trigger sources
   * (the 30s timer, the "Refresh" button via manualRefresh$, and the range
   * selector via rangeChange$) are merged into ONE stream feeding a
   * single switchMap, so there is only ever one in-flight request — changing
   * the range mid-poll cancels any pending fetch instead of racing it.
   */
  private startGrowthPolling(): void {
    merge(timer(0, METRICS_POLL_INTERVAL_MS), this.manualRefresh$, this.rangeChange$)
      .pipe(
        switchMap(() =>
          this.metricsService.getGrowthMetrics(RANGE_TO_WEEKS[this.selectedRange()]).pipe(
            tap(() => this.growthError.set(false)),
            catchError(() => {
              this.growthError.set(true);
              this.growthLoading.set(false);
              return EMPTY;
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((data) => {
        this.growth.set(data);
        this.growthLoading.set(false);
      });
  }

  /** ETL pipeline status polls every 30s, plus an immediate tick from onRefresh(). */
  private startEtlPolling(): void {
    merge(timer(0, METRICS_POLL_INTERVAL_MS), this.manualRefresh$)
      .pipe(
        switchMap(() =>
          this.metricsService.getEtlStatus().pipe(
            tap(() => this.etlError.set(false)),
            catchError(() => {
              this.etlError.set(true);
              this.etlLoading.set(false);
              return EMPTY;
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((data) => {
        this.etlStatuses.set(data);
        this.etlLoading.set(false);
      });
  }

  /** Resources poll every 30s independently, only while this page is mounted. */
  private startResourcesPolling(): void {
    timer(0, METRICS_POLL_INTERVAL_MS)
      .pipe(
        switchMap(() =>
          this.metricsService.getResourceMetrics().pipe(
            tap(() => this.resourcesError.set(false)),
            catchError(() => {
              this.resourcesError.set(true);
              this.resourcesLoading.set(false);
              return EMPTY;
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((data) => {
        this.resources.set(data);
        this.resourcesLoading.set(false);
      });
  }

  // ── User actions ───────────────────────────────────────────────────────────────

  /**
   * Manual "refresh now". Everything already polls automatically every 30s, so
   * this simply pushes an extra tick into the shared manualRefresh$ trigger —
   * each section's own switchMap (see start*Polling above) folds it into the
   * same subscription as the timer, guaranteeing no duplicate in-flight HTTP
   * calls per section.
   */
  onRefresh(): void {
    if (this.isRefreshing()) return;
    this.manualRefresh$.next();
  }

  onRangeChange(range: RangeOption): void {
    if (this.selectedRange() === range) return;
    this.selectedRange.set(range);
    this.rangeChange$.next();
  }

  onSort(field: SortableField): void {
    if (this.sortField() === field) {
      this.sortDir.update((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      this.sortField.set(field);
      this.sortDir.set('desc');
    }
  }

  onExportCsv(): void {
    this.exportingCsv.set(true);
    this.metricsService
      .exportTenantsCsv()
      .pipe(
        catchError(() => {
          this.notifications.error(this.transloco.translate('admin.metrics.exportError'));
          return EMPTY;
        }),
        finalize(() => this.exportingCsv.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((blob) => this.downloadFile(blob, `tenants-metrics-${this.todayStamp()}.csv`));
  }

  private downloadFile(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  }

  private todayStamp(): string {
    const d = new Date();
    const yyyy = d.getFullYear();
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${yyyy}-${mm}-${dd}`;
  }

  // ── Presentation helpers ────────────────────────────────────────────────────────

  isSortActive(field: SortableField): boolean {
    return this.sortField() === field;
  }

  barHeightPercent(value: number): number {
    return Math.round((value / this.growthChartMax()) * 100);
  }

  pluginBarPercent(count: number): number {
    return Math.round((count / this.topPluginMax()) * 100);
  }

  fcrTier(fcrPercentage: number): 'good' | 'warn' | 'bad' {
    if (fcrPercentage >= 70) return 'good';
    if (fcrPercentage >= 50) return 'warn';
    return 'bad';
  }

  /** Thresholds for the tenant ranking's agent-limit utilization bar. */
  utilizationTier(percent: number): UtilizationTier {
    if (percent < 50) return 'low';
    if (percent < 80) return 'mid';
    return 'high';
  }

  /** Thresholds for CPU / heap / DB-pool bars — different cutoffs than utilizationTier. */
  resourceTier(percent: number): UtilizationTier {
    if (percent < 60) return 'low';
    if (percent < 85) return 'mid';
    return 'high';
  }

  isDlqWarning(queue: QueueDepth): boolean {
    return queue.messageCount > 0 && /dlq/i.test(queue.queueName);
  }

  /** Human-readable label for a raw backend channel key (falls back to the raw key). */
  channelLabel(channel: string): string {
    const key = CHANNEL_LABEL_KEYS[channel];
    return key ? this.transloco.translate(key) : channel;
  }

  /** Contact count for a given tenant row and channel — 0 when the channel key is absent. */
  countFor(row: TenantChannelRow, channel: string): number {
    return row.countsByChannel[channel] ?? 0;
  }

  etlVisualStatus(row: EtlTableStatus): EtlVisualStatus {
    if (row.status === 'ERROR') return 'error';
    if (row.status === 'RUNNING') return 'running';
    if (row.status === 'IDLE') return 'idle';
    // DONE, but stale beyond the lag threshold — surface it as a warning.
    if (row.lagMinutes !== null && row.lagMinutes > ETL_LAG_WARNING_THRESHOLD_MINUTES) {
      return 'warning';
    }
    return 'done';
  }

  formatDuration(seconds: number | null | undefined): string {
    if (seconds === null || seconds === undefined) return '—';
    const m = Math.floor(seconds / 60);
    const s = Math.round(seconds % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  }

  formatUptime(seconds: number): string {
    const days = Math.floor(seconds / 86400);
    const hours = Math.floor((seconds % 86400) / 3600);
    const dLabel = this.transloco.translate('admin.metrics.uptimeDays');
    const hLabel = this.transloco.translate('admin.metrics.uptimeHours');
    return `${days} ${dLabel} ${hours} ${hLabel}`;
  }

  formatBytes(bytes: number): string {
    const mb = bytes / (1024 * 1024);
    if (mb >= 1024) {
      return `${(mb / 1024).toFixed(1)} GB`;
    }
    return `${Math.round(mb)} MB`;
  }

  formatRelativePast(dateStr: string | null): string {
    if (!dateStr) return '—';
    const date = new Date(dateStr);
    if (isNaN(date.getTime())) return '—';

    const diffMs = Date.now() - date.getTime();
    const diffMin = Math.round(diffMs / 60000);

    if (diffMin < 1) return this.transloco.translate('admin.metrics.etlJustNow');
    if (diffMin < 60) {
      return this.transloco.translate('admin.metrics.etlMinutesAgo', { count: diffMin });
    }
    const diffHours = Math.floor(diffMin / 60);
    if (diffHours < 24) {
      return this.transloco.translate('admin.metrics.etlHoursAgo', { count: diffHours });
    }
    return date.toLocaleString('pl-PL', {
      day: '2-digit',
      month: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    });
  }
}
