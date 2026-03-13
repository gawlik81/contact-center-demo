import { inject } from '@angular/core';
import { CanActivateFn, Router, ActivatedRouteSnapshot } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { UserRole } from '../models/jwt-payload.model';

/**
 * Functional guard that checks the user's role against the allowed roles
 * defined in the route's `data.roles` array.
 *
 * Usage in route config:
 *   data: { roles: ['ADMIN'] as UserRole[] }
 *   canActivate: [authGuard, roleGuard]
 */
export const roleGuard: CanActivateFn = (route: ActivatedRouteSnapshot, _state) => {
  const authService = inject(AuthService);
  const router = inject(Router);

  const allowedRoles = (route.data?.['roles'] as UserRole[] | undefined) ?? [];
  const userRole = authService.getUserRole();

  if (!userRole || !allowedRoles.includes(userRole)) {
    return router.createUrlTree(['/forbidden']);
  }

  return true;
};
