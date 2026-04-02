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
        path: 'customers/import',
        data: { breadcrumb: 'Import CSV', roles: ['SUPERVISOR', 'ADMIN'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./pages/customers/customer-import/customer-import.component').then(
            (m) => m.CustomerImportComponent,
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
          import('./pages/reports/reports-placeholder.component').then((m) => m.ReportsComponent),
      },
      {
        path: 'settings',
        data: { breadcrumb: 'Konfiguracja', roles: ['SUPERVISOR', 'ADMIN'] },
        canActivate: [roleGuard],
        children: [
          { path: '', redirectTo: 'email', pathMatch: 'full' },
          {
            path: 'email',
            data: { breadcrumb: 'Email' },
            loadComponent: () =>
              import('./pages/settings/email-settings.component').then(
                (m) => m.EmailSettingsComponent,
              ),
          },
          {
            path: 'twilio',
            data: { breadcrumb: 'Twilio VoIP' },
            loadComponent: () =>
              import('./pages/settings/twilio-settings.component').then(
                (m) => m.TwilioSettingsComponent,
              ),
          },
        ],
      },
      {
        path: 'ivr',
        data: { breadcrumb: 'Edytor IVR', roles: ['SUPERVISOR', 'ADMIN'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./pages/ivr/ivr-list/ivr-list.component').then((m) => m.IvrListComponent),
      },
      {
        path: 'ivr/:ivrId',
        data: { breadcrumb: 'Edytor', roles: ['SUPERVISOR', 'ADMIN'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./pages/ivr/ivr-editor/ivr-editor.component').then((m) => m.IvrEditorComponent),
      },
    ],
  },
];
