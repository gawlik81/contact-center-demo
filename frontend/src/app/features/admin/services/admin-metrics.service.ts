import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
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
import { environment } from '../../../../environments/environment';
import { GlobalMetrics, TenantMetricsDetail } from '../models/admin-metrics.model';

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
   * Polling stream: fires immediately, then every 30 s.
   * refCount: false – the timer keeps running regardless of subscriber count,
   * so navigating away and back does NOT restart the poll cycle or lose cached state.
   * Errors are caught inside the stream so the timer is never terminated.
   */
  private readonly _poll$ = timer(0, POLL_INTERVAL_MS).pipe(
    switchMap(() =>
      this.http.get<GlobalMetrics>(this.baseUrl).pipe(
        tap((metrics) => {
          this._metrics$.next(metrics);
          this._loading$.next(false);
          this._error$.next(false);
        }),
        catchError(() => {
          this._error$.next(true);
          this._loading$.next(false);
          // Return EMPTY so switchMap completes this inner observable without
          // propagating the error to the outer timer stream.
          return EMPTY;
        }),
      ),
    ),
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
    // Kick off the polling loop immediately when the service is instantiated.
    // The subscription is intentionally never unsubscribed – this service is a
    // root singleton and lives for the entire app lifetime.
    this._poll$.subscribe();
  }

  getTenantMetrics(id: string): Observable<TenantMetricsDetail> {
    return this.http.get<TenantMetricsDetail>(`${this.baseUrl}/tenants/${id}`);
  }
}
