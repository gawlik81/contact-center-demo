import { Routes } from '@angular/router';

export const SUPERVISOR_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./supervisor-shell.component').then((m) => m.SupervisorShellComponent),
    data: { breadcrumb: 'Supervisor' },
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
          import('./supervisor-dashboard.component').then((m) => m.SupervisorDashboardComponent),
      },
      {
        path: 'agents',
        data: { breadcrumb: 'Agenci' },
        loadComponent: () =>
          import('./pages/users/user-list/user-list.component').then((m) => m.UserListComponent),
      },
      {
        path: 'queues',
        data: { breadcrumb: 'Kolejki' },
        loadComponent: () =>
          import('./supervisor-dashboard.component').then((m) => m.SupervisorDashboardComponent),
      },
      {
        path: 'campaigns',
        data: { breadcrumb: 'Kampanie' },
        loadComponent: () =>
          import('./supervisor-dashboard.component').then((m) => m.SupervisorDashboardComponent),
      },
      {
        path: 'customers',
        data: { breadcrumb: 'Klienci' },
        loadComponent: () =>
          import('./supervisor-dashboard.component').then((m) => m.SupervisorDashboardComponent),
      },
      {
        path: 'reports',
        data: { breadcrumb: 'Raporty' },
        loadComponent: () =>
          import('./supervisor-dashboard.component').then((m) => m.SupervisorDashboardComponent),
      },
      {
        path: 'settings',
        data: { breadcrumb: 'Konfiguracja' },
        loadComponent: () =>
          import('./supervisor-dashboard.component').then((m) => m.SupervisorDashboardComponent),
      },
    ],
  },
];
