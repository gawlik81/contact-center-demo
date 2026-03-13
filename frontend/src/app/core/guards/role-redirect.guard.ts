import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';

/**
 * Guard for the root route `/`.
 * Redirects authenticated users to their role-based default route.
 * Unauthenticated users are redirected to /login.
 */
export const roleRedirectGuard: CanActivateFn = (_route, _state) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  if (!authService.isAuthenticated()) {
    return router.createUrlTree(['/login']);
  }

  const role = authService.getUserRole();
  if (!role) {
    return router.createUrlTree(['/forbidden']);
  }

  return router.createUrlTree([authService.getRoleDefaultRoute(role)]);
};
