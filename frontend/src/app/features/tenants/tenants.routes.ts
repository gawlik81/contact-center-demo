import { Routes } from '@angular/router';

export const TENANT_ROUTES: Routes = [
  {
    path: '',
    data: { breadcrumb: 'Tenanci' },
    loadComponent: () =>
      import('./tenant-list/tenant-list.component').then((m) => m.TenantListComponent),
  },
];
