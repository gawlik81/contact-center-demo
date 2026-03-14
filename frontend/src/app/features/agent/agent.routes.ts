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
          import('./agent-dashboard.component').then((m) => m.AgentDashboardComponent),
      },
      {
        path: 'customers',
        data: { breadcrumb: 'Klienci' },
        loadComponent: () =>
          import('./agent-dashboard.component').then((m) => m.AgentDashboardComponent),
      },
    ],
  },
];
