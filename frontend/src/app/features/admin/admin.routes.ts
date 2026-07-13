import { Routes } from '@angular/router';

export const ADMIN_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./admin-shell.component').then((m) => m.AdminShellComponent),
    data: { breadcrumb: 'role.superAdmin' },
    children: [
      {
        path: '',
        redirectTo: 'dashboard',
        pathMatch: 'full',
      },
      {
        path: 'dashboard',
        data: { breadcrumb: 'nav.dashboard' },
        loadComponent: () =>
          import('./pages/dashboard/admin-dashboard.component').then(
            (m) => m.AdminDashboardComponent,
          ),
      },
      {
        path: 'tenants',
        data: { breadcrumb: 'nav.tenants' },
        loadChildren: () => import('../tenants/tenants.routes').then((m) => m.TENANT_ROUTES),
      },
      {
        path: 'users',
        data: { breadcrumb: 'nav.users' },
        loadComponent: () =>
          import('./pages/users/admin-users.component').then((m) => m.AdminUsersComponent),
      },
      {
        path: 'metrics',
        data: { breadcrumb: 'nav.metrics' },
        loadComponent: () =>
          import('./pages/metrics/admin-metrics-page.component').then(
            (m) => m.AdminMetricsPageComponent,
          ),
      },
    ],
  },
];
