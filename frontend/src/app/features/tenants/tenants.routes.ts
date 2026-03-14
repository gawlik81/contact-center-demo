import { Routes } from '@angular/router';

export const TENANT_ROUTES: Routes = [
  {
    path: '',
    data: { breadcrumb: 'Tenanci' },
    loadComponent: () =>
      import('./tenant-list/tenant-list.component').then((m) => m.TenantListComponent),
  },
  {
    path: 'new',
    data: { breadcrumb: 'Nowy tenant' },
    loadComponent: () =>
      import('./tenant-form/tenant-form.component').then((m) => m.TenantFormComponent),
  },
];
