import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import {
  Observable,
  BehaviorSubject,
  timer,
  switchMap,
  shareReplay,
  catchError,
  EMPTY,
  tap,
  map,
} from 'rxjs';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { environment } from '../../../../environments/environment';
import {
  ContactChannelMatrix,
  EtlTableStatus,
  GlobalMetrics,
  GrowthMetrics,
  SystemResourceMetrics,
  TenantMetricsDetail,
  UsageMetrics,
} from '../models/admin-metrics.model';
import { AuthService } from '../../../core/services/auth.service';

const POLL_INTERVAL_MS = 30_000;

const EMPTY_METRICS: GlobalMetrics = {
  totalActiveTenants: 0,
  totalAgentsOnline: 0,
  totalActiveContacts: 0,
  systemAlerts: [],
  tenants: [],
  generatedAt: '',
};

@Injectable({ providedIn: 'root' })
export class AdminMetricsService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly baseUrl = `${environment.apiUrl}/admin/metrics`;
  private readonly etlUrl = `${environment.apiUrl}/admin/etl`;

  /**
   * Internal state store. Holds the last successfully fetched metrics.
   * Initialized with empty metrics so consumers never receive null.
   */
  private readonly _metrics$ = new BehaviorSubject<GlobalMetrics>(EMPTY_METRICS);

  /**
   * Internal loading flag. True only on the very first fetch.
   */
  private readonly _loading$ = new BehaviorSubject<boolean>(true);

  /**
   * Internal error flag. Set when a poll cycle fails; cleared on next success.
   */
  private readonly _error$ = new BehaviorSubject<boolean>(false);

  /**
   * Polling stream driven by the current user role.
   *
   * The outer switchMap listens to role changes via toObservable(). When the
   * role is 'SUPER_ADMIN' it starts timer(0, POLL_INTERVAL_MS); for any other role
   * (ADMIN, SUPERVISOR, AGENT, null) it emits EMPTY, which means no timer is created
   * and any running timer from a previous SUPER_ADMIN session is automatically
   * cancelled by switchMap's unsubscription logic.
   *
   * refCount: true — the shareReplay unsubscribes (and stops the timer) when
   * all consumers unsubscribe, preventing a memory leak when the admin logs out
   * or navigates away permanently. The BehaviorSubject (_metrics$) retains the
   * last cached value for instant replay when a new subscriber arrives.
   */
  private readonly _poll$: Observable<GlobalMetrics> = toObservable(this.auth.currentRole).pipe(
    switchMap((role) => {
      if (role !== 'SUPER_ADMIN') {
        // Not the super admin – emit nothing. Any running timer from a prior
        // SUPER_ADMIN session is torn down by switchMap automatically.
        return EMPTY;
      }
      return timer(0, POLL_INTERVAL_MS).pipe(
        switchMap(() =>
          this.http.get<GlobalMetrics>(this.baseUrl).pipe(
            tap((metrics) => {
              this._metrics$.next(metrics);
              this._loading$.next(false);
              this._error$.next(false);
            }),
            catchError((err: HttpErrorResponse) => {
              // 403 means the JWT lost SUPER_ADMIN access mid-session.
              // Do NOT set the error flag – silently drop this tick.
              if (err.status === 403) {
                return EMPTY;
              }
              this._error$.next(true);
              this._loading$.next(false);
              return EMPTY;
            }),
          ),
        ),
      );
    }),
    shareReplay({ bufferSize: 1, refCount: true }),
  );

  /**
   * Public read-only stream of the latest metrics.
   * Always emits the last known value immediately (BehaviorSubject replay).
   * Multiple subscribers share the same value — no extra HTTP calls.
   */
  readonly globalMetrics$: Observable<GlobalMetrics> = this._metrics$.asObservable();

  /**
   * True only during the very first load (before any response arrives).
   */
  readonly loading$: Observable<boolean> = this._loading$.asObservable();

  /**
   * True when the last poll cycle returned an HTTP error.
   */
  readonly error$: Observable<boolean> = this._error$.asObservable();

  /**
   * Derived: number of active system alerts (for badge display in sidenav/topbar).
   */
  readonly alertCount$: Observable<number> = this._metrics$.pipe(
    map((m) => m.systemAlerts?.length ?? 0),
  );

  /**
   * Signal-based subscription to the poll stream.
   * toSignal() manages the subscription lifetime via the injection context,
   * replacing the bare .subscribe() call in the constructor.
   */
  private readonly _pollSignal = toSignal(this._poll$);

  getTenantMetrics(id: string): Observable<TenantMetricsDetail> {
    return this.http.get<TenantMetricsDetail>(`${this.baseUrl}/tenants/${id}`);
  }

  /**
   * One-shot snapshot of the same payload as {@link globalMetrics$}, but bypassing
   * the shared polling stream entirely. Used by the "Metryki platformy" page, which
   * fetches this data once on entry plus on manual refresh — NOT every 30s — so it
   * must not attach itself to (or be throttled/accelerated by) the Dashboard's
   * continuous polling subscription.
   */
  getGlobalMetricsSnapshot(): Observable<GlobalMetrics> {
    return this.http.get<GlobalMetrics>(this.baseUrl);
  }

  /** Today's platform-wide usage KPIs (contacts handled, AHT, ASA, FCR, campaigns). */
  getUsageMetrics(): Observable<UsageMetrics> {
    return this.http.get<UsageMetrics>(`${this.baseUrl}/usage`);
  }

  /** Weekly tenant/user growth points plus top installed plugins. */
  getGrowthMetrics(weeks = 6): Observable<GrowthMetrics> {
    const params = new HttpParams().set('weeks', weeks.toString());
    return this.http.get<GrowthMetrics>(`${this.baseUrl}/growth`, { params });
  }

  /**
   * Shared-process system resource metrics (CPU, JVM heap, DB pool, Redis, RabbitMQ).
   * Intended to be polled every 30s by the caller while the metrics page is active —
   * this method itself is a single request per call, no internal timer.
   */
  getResourceMetrics(): Observable<SystemResourceMetrics> {
    return this.http.get<SystemResourceMetrics>(`${this.baseUrl}/resources`);
  }

  /** CSV export of the tenant ranking table. */
  exportTenantsCsv(): Observable<Blob> {
    return this.http.get(`${this.baseUrl}/tenants/export`, { responseType: 'blob' });
  }

  /** ETL pipeline sync status per source table (lag, last sync, row counts). */
  getEtlStatus(): Observable<EtlTableStatus[]> {
    return this.http.get<EtlTableStatus[]>(`${this.etlUrl}/status`);
  }

  /**
   * Contact counts per tenant, broken down by channel, for the given day range
   * (5-min cached, same cache as /usage). `days` must be one of 7, 30, 90 — the
   * backend rejects any other value with 400/422.
   */
  getContactChannelMatrix(days: number): Observable<ContactChannelMatrix> {
    const params = new HttpParams().set('days', days.toString());
    return this.http.get<ContactChannelMatrix>(`${this.baseUrl}/contacts-by-channel`, { params });
  }
}
