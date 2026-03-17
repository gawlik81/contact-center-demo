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
import { toObservable } from '@angular/core/rxjs-interop';
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
   * role is 'ADMIN' it starts timer(0, POLL_INTERVAL_MS); for any other role
   * (SUPERVISOR, AGENT, null) it emits EMPTY, which means no timer is created
   * and any running timer from a previous ADMIN session is automatically
   * cancelled by switchMap's unsubscription logic.
   *
   * This makes the guard reactive instead of a one-shot constructor check,
   * correctly handling the scenario where an ADMIN logs out and a SUPERVISOR
   * logs in within the same browser tab without a page reload.
   *
   * On HTTP 403 inside the inner pipe: the error is caught and EMPTY is
   * returned, completing that single tick's inner observable. The outer timer
   * continues so subsequent ticks will retry – but since a 403 means the token
   * itself does not have ADMIN access the role signal will have changed and
   * the outer switchMap will have already torn down the timer. This is a
   * defense-in-depth guard against any residual 403 propagating as a toast.
   *
   * refCount: false – the shareReplay stays connected even when all downstream
   * consumers unsubscribe (e.g. navigating away from the dashboard), so cached
   * metrics survive navigation and are replayed instantly on return.
   */
  private readonly _poll$ = toObservable(this.auth.currentRole).pipe(
    switchMap((role) => {
      if (role !== 'ADMIN') {
        // Not an admin – emit nothing. Any running timer from a prior ADMIN
        // session is torn down by switchMap automatically.
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
              // 403 means the JWT lost ADMIN access mid-session.
              // Do NOT set the error flag – silently drop this tick.
              // The outer role-based switchMap will already be tearing down
              // the timer because the role signal will have been updated.
              if (err.status === 403) {
                return EMPTY;
              }
              this._error$.next(true);
              this._loading$.next(false);
              // Return EMPTY so switchMap completes this inner observable
              // without propagating the error to the outer timer stream.
              return EMPTY;
            }),
          ),
        ),
      );
    }),
    shareReplay({ bufferSize: 1, refCount: false }),
  );

  /**
   * Public read-only stream of the latest metrics.
   * Always emits the last known value immediately (BehaviorSubject replay).
   * Multiple subscribers share the same value – no extra HTTP calls.
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

  constructor() {
    // Subscribe unconditionally. The _poll$ stream is self-guarding: its
    // outer switchMap gates on the currentRole signal and only creates the
    // timer when role === 'ADMIN'. For SUPERVISOR / AGENT the inner stream
    // is EMPTY (no HTTP calls). When role transitions away from ADMIN the
    // running timer is automatically cancelled by switchMap.
    this._poll$.subscribe();
  }

  getTenantMetrics(id: string): Observable<TenantMetricsDetail> {
    return this.http.get<TenantMetricsDetail>(`${this.baseUrl}/tenants/${id}`);
  }
}
