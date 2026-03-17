import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

/**
 * Holds mutable refresh state shared between concurrent requests.
 * Extracted from module scope to avoid race conditions caused by multiple
 * instances of the interceptor function closing over the same module-level
 * variable when Angular creates more than one injector (e.g. during testing).
 */
@Injectable({ providedIn: 'root' })
export class TokenRefreshService {
  isRefreshing = false;
  readonly refreshTokenSubject = new BehaviorSubject<string | null>(null);
}
