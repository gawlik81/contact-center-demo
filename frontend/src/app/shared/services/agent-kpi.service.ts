import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, EMPTY, timer, switchMap, catchError, shareReplay } from 'rxjs';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { environment } from '../../../environments/environment';
import { AgentKpiResponse } from '../models/agent-kpi.model';
import { AuthService } from '../../core/services/auth.service';

const POLL_INTERVAL_MS = 60_000;

@Injectable({ providedIn: 'root' })
export class AgentKpiService {
  private readonly http = inject(HttpClient);
  private readonly auth = inject(AuthService);
  private readonly url = `${environment.apiUrl}/agent/me/kpi`;

  /**
   * Polling stream — active only when the current role is AGENT.
   * Emits the latest KPI snapshot or null when a request fails.
   * refCount: true stops the timer when all consumers unsubscribe.
   */
  readonly kpi$: Observable<AgentKpiResponse | null> = toObservable(this.auth.currentRole).pipe(
    switchMap((role) => {
      if (role !== 'AGENT') {
        return EMPTY;
      }
      return timer(0, POLL_INTERVAL_MS).pipe(
        switchMap(() =>
          this.http.get<AgentKpiResponse>(this.url).pipe(
            catchError(() => [null]),
          ),
        ),
      );
    }),
    shareReplay({ bufferSize: 1, refCount: true }),
  );

  /** Signal-based handle to keep the subscription alive via injection context. */
  private readonly _kpiSignal = toSignal(this.kpi$);
}
