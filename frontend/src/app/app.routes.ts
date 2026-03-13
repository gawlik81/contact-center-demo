import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { roleGuard } from './core/guards/role.guard';
import { roleRedirectGuard } from './core/guards/role-redirect.guard';
import { UserRole } from './core/models/jwt-payload.model';

export const routes: Routes = [
  // Root – redirect based on role
  {
    path: '',
    canActivate: [roleRedirectGuard],
    // roleRedirectGuard always returns a UrlTree, so this component is never rendered
    loadComponent: () =>
      import('./features/auth/login/login.component').then((m) => m.LoginComponent),
  },

  // Auth – public
  {
    path: 'login',
    loadChildren: () =>
      import('./features/auth/login/login.routes').then((m) => m.LOGIN_ROUTES),
  },

  // Forbidden – public
  {
    path: 'forbidden',
    loadComponent: () =>
      import('./features/auth/forbidden/forbidden.component').then((m) => m.ForbiddenComponent),
  },

  // Admin – protected: ADMIN role only
  {
    path: 'admin',
    canActivate: [authGuard, roleGuard],
    data: { roles: ['ADMIN'] as UserRole[] },
    loadChildren: () =>
      import('./features/admin/admin.routes').then((m) => m.ADMIN_ROUTES),
  },

  // Supervisor – protected: SUPERVISOR role only
  {
    path: 'supervisor',
    canActivate: [authGuard, roleGuard],
    data: { roles: ['SUPERVISOR'] as UserRole[] },
    loadChildren: () =>
      import('./features/supervisor/supervisor.routes').then((m) => m.SUPERVISOR_ROUTES),
  },

  // Agent – protected: AGENT role only
  {
    path: 'agent',
    canActivate: [authGuard, roleGuard],
    data: { roles: ['AGENT'] as UserRole[] },
    loadChildren: () =>
      import('./features/agent/agent.routes').then((m) => m.AGENT_ROUTES),
  },

  // Wildcard – redirect to root (which handles role-based redirect)
  {
    path: '**',
    redirectTo: '',
  },
];
