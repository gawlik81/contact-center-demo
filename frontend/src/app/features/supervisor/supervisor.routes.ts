import { Routes } from '@angular/router';

export const SUPERVISOR_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./supervisor-shell.component').then((m) => m.SupervisorShellComponent),
    children: [
      {
        path: '',
        loadComponent: () =>
          import('./supervisor-dashboard.component').then((m) => m.SupervisorDashboardComponent),
      },
    ],
  },
];
