import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
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
import { GlobalMetrics, TenantMetricsDetail } from '../models/admin-metrics.model';
import { AuthService } from '../../../core/services/auth.service';

const POLL_INTERVAL_MS = 30_000;

const EMPTY_METRICS: GlobalMetrics = {
  totalActiveTenants: 0,
  totalAgentsOnline: 0,
  systemAlerts: [],
  tenants: [],
  generatedAt: '',
};

@Injectable({ providedIn: 'root' })
export class AdminMetricsService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly baseUrl = `${environment.apiUrl}/admin/metrics`;

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
}
