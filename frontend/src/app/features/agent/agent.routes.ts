import { Routes } from '@angular/router';

export const AGENT_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./agent-shell.component').then((m) => m.AgentShellComponent),
    data: { breadcrumb: 'Agent' },
    children: [
      {
        path: '',
        redirectTo: 'desktop',
        pathMatch: 'full',
      },
      {
        path: 'desktop',
        data: { breadcrumb: 'Desktop' },
        loadComponent: () =>
          import('./pages/agent-desktop/agent-desktop.component').then(
            (m) => m.AgentDesktopComponent,
          ),
      },
      {
        // TODO FE-015: Replace placeholder with real Agent Customers component
        path: 'customers',
        data: { breadcrumb: 'Klienci' },
        loadComponent: () =>
          import('./pages/customers/agent-customers-placeholder.component').then(
            (m) => m.AgentCustomersPlaceholderComponent,
          ),
      },
    ],
  },
];
