import { Routes } from '@angular/router';

export const AGENT_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./agent-shell.component').then((m) => m.AgentShellComponent),
    children: [
      {
        path: '',
        loadComponent: () =>
          import('./agent-dashboard.component').then((m) => m.AgentDashboardComponent),
      },
    ],
  },
];
