import { ChangeDetectionStrategy, Component } from '@angular/core';
import { AdminUserListComponent } from './admin-user-list/admin-user-list.component';

/**
 * Panel Admina – zakładka "Użytkownicy".
 *
 * Wyswietla uzytkownikow ze WSZYSTKICH tenantow (cross-tenant).
 * Deleguje logike do AdminUserListComponent, ktory komunikuje sie
 * z GET /api/admin/users (cross-tenant endpoint dla roli ADMIN).
 */
@Component({
  selector: 'cc-admin-users',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [AdminUserListComponent],
  template: `<cc-admin-user-list />`,
})
export class AdminUsersComponent {}
