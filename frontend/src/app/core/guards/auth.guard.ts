import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map, catchError, of } from 'rxjs';
import { AuthService } from '../services/auth.service';
import { TokenService } from '../services/token.service';

export const authGuard: CanActivateFn = (_route, _state) => {
  const authService = inject(AuthService);
  const tokenService = inject(TokenService);
  const router = inject(Router);

  const token = tokenService.getAccessToken();

  // Access token is present and valid in memory — allow navigation
  if (token && !tokenService.isTokenExpired(token) && authService.isAuthenticated()) {
    return true;
  }

  // No in-memory token (page reload) but refresh token may exist in sessionStorage.
  // Attempt silent refresh before redirecting to login.
  if (tokenService.getRefreshToken()) {
    return authService.refresh().pipe(
      map(() => true),
      // A transient refresh failure (network/5xx) lands here too: tokens are kept (no logout), the
      // user sees the login page and the next guarded navigation retries the refresh (FE-173, R3).
      catchError(() => of(router.createUrlTree(['/auth/login']))),
    );
  }

  // No tokens at all — redirect to login
  tokenService.clearAll();
  return router.createUrlTree(['/auth/login']);
};
