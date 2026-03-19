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
        // TODO FE-010: Replace placeholder with real Queues component
        path: 'queues',
        data: { breadcrumb: 'Kolejki' },
        loadComponent: () =>
          import('./pages/queues/queues-placeholder.component').then(
            (m) => m.QueuesPlaceholderComponent,
          ),
      },
      {
        // TODO FE-011: Replace placeholder with real Campaigns component
        path: 'campaigns',
        data: { breadcrumb: 'Kampanie' },
        loadComponent: () =>
          import('./pages/campaigns/campaigns-placeholder.component').then(
            (m) => m.CampaignsPlaceholderComponent,
          ),
      },
      {
        path: 'customers',
        data: { breadcrumb: 'Klienci' },
        loadComponent: () =>
          import('./pages/customers/customer-list/customer-list.component').then(
            (m) => m.CustomerListComponent,
          ),
      },
      {
        // TODO FE-013: Replace placeholder with real Reports component
        path: 'reports',
        data: { breadcrumb: 'Raporty' },
        loadComponent: () =>
          import('./pages/reports/reports-placeholder.component').then(
            (m) => m.ReportsPlaceholderComponent,
          ),
      },
      {
        // TODO FE-014: Replace placeholder with real Settings component
        path: 'settings',
        data: { breadcrumb: 'Konfiguracja' },
        loadComponent: () =>
          import('./pages/settings/settings-placeholder.component').then(
            (m) => m.SettingsPlaceholderComponent,
          ),
      },
    ],
  },
];
