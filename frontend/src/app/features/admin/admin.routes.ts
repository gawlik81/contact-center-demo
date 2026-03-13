import { Routes } from '@angular/router';

export const ADMIN_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./admin-shell.component').then((m) => m.AdminShellComponent),
    children: [
      {
        path: '',
        loadComponent: () =>
          import('./admin-dashboard.component').then((m) => m.AdminDashboardComponent),
      },
    ],
  },
];
