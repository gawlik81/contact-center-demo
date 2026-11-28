import { Injectable } from '@angular/core';
import { ReplaySubject } from 'rxjs';

/** Outcome of one refresh round, delivered to every request queued behind it (FE-173). */
export type RefreshOutcome = { token: string } | { error: unknown };

/**
 * Holds mutable refresh state shared between concurrent requests.
 * Extracted from module scope to avoid race conditions caused by multiple
 * instances of the interceptor function closing over the same module-level
 * variable when Angular creates more than one injector (e.g. during testing).
 */
@Injectable({ providedIn: 'root' })
export class TokenRefreshService {
  isRefreshing = false;
  /**
   * Result of the CURRENT refresh round (token OR error) for the initiating and the queued requests.
   * Replaced with a fresh ReplaySubject per round by the interceptor: a result emitted before a
   * waiter subscribes is not lost, and failures are values (error() would kill a shared subject).
   */
  refreshOutcome$ = new ReplaySubject<RefreshOutcome>(1);
}
