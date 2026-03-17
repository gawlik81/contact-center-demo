import { Routes } from '@angular/router';

export const ADMIN_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./admin-shell.component').then((m) => m.AdminShellComponent),
    data: { breadcrumb: 'Admin' },
    children: [
      {
        path: '',
        redirectTo: 'dashboard',
        pathMatch: 'full',
      },
      {
        path: 'dashboard',
        data: { breadcrumb: 'Dashboard' },
        loadComponent: () =>
          import('./pages/dashboard/admin-dashboard.component').then(
            (m) => m.AdminDashboardComponent,
          ),
      },
      {
        path: 'tenants',
        data: { breadcrumb: 'Tenanci' },
        loadChildren: () => import('../tenants/tenants.routes').then((m) => m.TENANT_ROUTES),
      },
      {
        path: 'users',
        data: { breadcrumb: 'Uzytkownicy' },
        loadComponent: () =>
          import('./pages/users/admin-users.component').then((m) => m.AdminUsersComponent),
      },
      {
        path: 'metrics',
        data: { breadcrumb: 'Metryki' },
        loadComponent: () =>
          import('./pages/metrics/admin-metrics-page.component').then(
            (m) => m.AdminMetricsPageComponent,
          ),
      },
    ],
  },
];
