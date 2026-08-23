import { Routes } from '@angular/router';
import { roleGuard } from '../../core/guards/role.guard';

export const SUPERVISOR_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./supervisor-shell.component').then((m) => m.SupervisorShellComponent),
    data: { breadcrumb: 'role.supervisor' },
    children: [
      {
        path: '',
        redirectTo: 'dashboard',
        pathMatch: 'full',
      },
      {
        path: 'dashboard',
        data: { breadcrumb: 'nav.dashboard', roles: ['ADMIN', 'SUPERVISOR'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./supervisor-dashboard.component').then((m) => m.SupervisorDashboardComponent),
      },
      {
        path: 'agents',
        data: { breadcrumb: 'nav.users', roles: ['ADMIN', 'SUPERVISOR'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./pages/users/user-list/user-list.component').then((m) => m.UserListComponent),
      },
      {
        path: 'queues',
        data: { breadcrumb: 'nav.queues', roles: ['SUPERVISOR', 'ADMIN'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./pages/queues/queue-list/queue-list.component').then(
            (m) => m.QueueListComponent,
          ),
      },
      {
        path: 'campaigns',
        data: { breadcrumb: 'nav.campaigns', roles: ['SUPERVISOR', 'ADMIN'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./pages/campaigns/campaign-list/campaign-list.component').then(
            (m) => m.CampaignListComponent,
          ),
      },
      {
        path: 'customers',
        data: { breadcrumb: 'nav.customers', roles: ['ADMIN', 'SUPERVISOR'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./pages/customers/customer-list/customer-list.component').then(
            (m) => m.CustomerListComponent,
          ),
      },
      {
        path: 'customers/import',
        data: { breadcrumb: 'nav.importCsv', roles: ['SUPERVISOR', 'ADMIN'] },
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
        data: { breadcrumb: 'nav.customerProfile', roles: ['SUPERVISOR', 'ADMIN'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./pages/customers/customer-detail/customer-detail.component').then(
            (m) => m.CustomerDetailComponent,
          ),
      },
      {
        path: 'reports',
        data: { breadcrumb: 'nav.reports', roles: ['SUPERVISOR', 'ADMIN'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./pages/reports/reports-placeholder.component').then((m) => m.ReportsComponent),
      },
      {
        path: 'reports/contacts',
        data: { breadcrumb: 'nav.reportsContacts', roles: ['SUPERVISOR', 'ADMIN'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./pages/contacts-report/contacts-report.component').then(
            (m) => m.ContactsReportComponent,
          ),
      },
      {
        path: 'settings',
        data: { breadcrumb: 'nav.configuration', roles: ['SUPERVISOR', 'ADMIN'] },
        canActivate: [roleGuard],
        children: [
          { path: '', redirectTo: 'email', pathMatch: 'full' },
          {
            path: 'email',
            data: { breadcrumb: 'nav.settingsEmail', roles: ['ADMIN'] },
            canActivate: [roleGuard],
            loadComponent: () =>
              import('./pages/settings/email-settings.component').then(
                (m) => m.EmailSettingsComponent,
              ),
          },
          {
            path: 'phone-numbers',
            data: { breadcrumb: 'nav.settingsPhoneNumbers', roles: ['ADMIN', 'SUPERVISOR'] },
            canActivate: [roleGuard],
            loadComponent: () =>
              import('./pages/settings/phone-numbers/phone-numbers.component').then(
                (m) => m.PhoneNumbersComponent,
              ),
          },
          {
            path: 'integrations',
            data: { breadcrumb: 'nav.settingsSocialMedia', roles: ['ADMIN'] },
            canActivate: [roleGuard],
            loadChildren: () =>
              import('../integrations/integrations.routes').then((m) => m.INTEGRATIONS_ROUTES),
          },
          {
            path: 'email-templates',
            data: { breadcrumb: 'nav.settingsEmailTemplates', roles: ['SUPERVISOR', 'ADMIN'] },
            canActivate: [roleGuard],
            loadComponent: () =>
              import('./pages/settings/email-templates/email-templates.component').then(
                (m) => m.EmailTemplatesComponent,
              ),
          },
          {
            path: 'disposition-sets',
            data: { breadcrumb: 'nav.settingsDispositionSets', roles: ['SUPERVISOR', 'ADMIN'] },
            canActivate: [roleGuard],
            loadComponent: () =>
              import('./pages/settings/disposition-sets/disposition-sets-page.component').then(
                (m) => m.DispositionSetsPageComponent,
              ),
          },
          {
            path: 'twilio',
            data: { breadcrumb: 'nav.settingsTwilioConfig', roles: ['ADMIN'] },
            canActivate: [roleGuard],
            loadComponent: () =>
              import('./pages/twilio-config/twilio-config.component').then(
                (m) => m.TwilioConfigComponent,
              ),
          },
          {
            path: 'ai-config',
            data: { breadcrumb: 'nav.settingsAiConfig', roles: ['ADMIN'] },
            canActivate: [roleGuard],
            loadComponent: () =>
              import('./pages/ai-config/ai-config.component').then((m) => m.AiConfigComponent),
          },
          {
            path: 'plugins',
            data: { breadcrumb: 'nav.settingsPlugins', roles: ['ADMIN'] },
            canActivate: [roleGuard],
            loadComponent: () =>
              import('./pages/settings/plugins/plugins-page.component').then(
                (m) => m.PluginsPageComponent,
              ),
          },
          {
            path: 'data-retention',
            data: { breadcrumb: 'nav.settingsDataRetention', roles: ['ADMIN'] },
            canActivate: [roleGuard],
            loadComponent: () =>
              import('./pages/settings/data-retention/data-retention.component').then(
                (m) => m.DataRetentionComponent,
              ),
          },
        ],
      },
      {
        path: 'agent-groups',
        data: { breadcrumb: 'nav.agentGroups', roles: ['SUPERVISOR', 'ADMIN'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./pages/agent-groups/agent-groups-page/agent-groups-page.component').then(
            (m) => m.AgentGroupsPageComponent,
          ),
      },
      {
        path: 'callbacks',
        data: { breadcrumb: 'nav.callbacks', roles: ['SUPERVISOR', 'ADMIN'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./pages/callbacks/supervisor-callbacks-page.component').then(
            (m) => m.SupervisorCallbacksPageComponent,
          ),
      },
      {
        path: 'ivr',
        data: { breadcrumb: 'nav.ivrEditor', roles: ['SUPERVISOR', 'ADMIN'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./pages/ivr/ivr-list/ivr-list.component').then((m) => m.IvrListComponent),
      },
      {
        path: 'ivr/:ivrId',
        data: { breadcrumb: 'nav.ivrEditor', roles: ['SUPERVISOR', 'ADMIN'] },
        canActivate: [roleGuard],
        loadComponent: () =>
          import('./pages/ivr/ivr-editor/ivr-editor.component').then((m) => m.IvrEditorComponent),
      },
    ],
  },
];
