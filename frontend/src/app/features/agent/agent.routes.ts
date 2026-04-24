import { Routes } from '@angular/router';

export const AGENT_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./agent-shell.component').then((m) => m.AgentShellComponent),
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
        path: 'customers',
        data: { breadcrumb: 'Klienci' },
        loadComponent: () =>
          import('./pages/customers/agent-customers-tab.component').then(
            (m) => m.AgentCustomersTabComponent,
          ),
      },
      {
        path: 'callbacks',
        data: { breadcrumb: 'Oddzwonienia' },
        loadComponent: () =>
          import('./pages/callbacks/agent-callbacks-page.component').then(
            (m) => m.AgentCallbacksPageComponent,
          ),
      },
    ],
  },
];
