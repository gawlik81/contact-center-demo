import { Routes } from '@angular/router';
import { roleGuard } from '../../core/guards/role.guard';

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
        data: { breadcrumb: 'Kolejki', roles: ['SUPERVISOR', 'ADMIN'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./pages/queues/queue-list/queue-list.component').then(
            (m) => m.QueueListComponent,
          ),
      },
      {
        path: 'campaigns',
        data: { breadcrumb: 'Kampanie', roles: ['SUPERVISOR', 'ADMIN'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./pages/campaigns/campaign-list/campaign-list.component').then(
            (m) => m.CampaignListComponent,
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
        path: 'customers/new',
        redirectTo: '/supervisor/customers',
        pathMatch: 'full',
      },
      {
        path: 'customers/:id',
        data: { breadcrumb: 'Profil klienta', roles: ['SUPERVISOR', 'ADMIN'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./pages/customers/customer-detail/customer-detail.component').then(
            (m) => m.CustomerDetailComponent,
          ),
      },
      {
        path: 'reports',
        data: { breadcrumb: 'Raporty', roles: ['SUPERVISOR', 'ADMIN'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./pages/reports/reports-placeholder.component').then(
            (m) => m.ReportsComponent,
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
