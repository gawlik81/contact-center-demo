import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { TokenService } from '../services/token.service';

export const authGuard: CanActivateFn = (_route, _state) => {
  const authService = inject(AuthService);
  const tokenService = inject(TokenService);
  const router = inject(Router);

  const token = tokenService.getAccessToken();

  if (!token) {
    return router.createUrlTree(['/login']);
  }

  if (tokenService.isTokenExpired(token)) {
    // Token expired – clear storage and redirect to login
    tokenService.clearAll();
    return router.createUrlTree(['/login']);
  }

  if (!authService.isAuthenticated()) {
    return router.createUrlTree(['/login']);
  }

  return true;
};
