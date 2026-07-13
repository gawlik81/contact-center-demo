import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { catchError, of, EMPTY } from 'rxjs';
import { AdminUserService } from '../../../services/admin-user.service';
import { TenantService } from '../../../../tenants/tenant.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { AuthService } from '../../../../../core/services/auth.service';
import { AdminPagedResponse, AdminUserResponse } from '../../../models/admin-user.model';
import { Tenant } from '../../../../tenants/tenant.model';
import { AdminUserFormComponent } from '../admin-user-form/admin-user-form.component';
import { UserRole, UserStatus } from '../../../../supervisor/models/user.model';

@Component({
  selector: 'cc-admin-user-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, ReactiveFormsModule, AdminUserFormComponent],
  templateUrl: './admin-user-list.component.html',
  styleUrl: './admin-user-list.component.scss',
})
export class AdminUserListComponent implements OnInit {
  private readonly adminUserService = inject(AdminUserService);
  private readonly tenantService = inject(TenantService);
  private readonly notifications = inject(NotificationService);
  private readonly auth = inject(AuthService);
  private readonly transloco = inject(TranslocoService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(false);
  readonly tenantsLoading = signal(false);
  readonly users = signal<AdminUserResponse[]>([]);
  readonly tenants = signal<Tenant[]>([]);
  readonly totalElements = signal(0);
  readonly totalPages = signal(0);
  readonly currentPage = signal(0);
  readonly pageSize = 20;

  // Modal formularza (tworzenie / edycja)
  readonly showFormModal = signal(false);
  readonly editingUser = signal<AdminUserResponse | null>(null);

  // Modal potwierdzenia usunięcia
  readonly showDeleteModal = signal(false);
  readonly deletingUser = signal<AdminUserResponse | null>(null);
  readonly deleteSubmitting = signal(false);

  // Modal potwierdzenia force-reset
  readonly showForceResetModal = signal(false);
  readonly forceResetUser = signal<AdminUserResponse | null>(null);
  readonly forceResetSubmitting = signal(false);

  readonly filterForm = this.fb.group({
    tenantId: ['' as string],
  });

  ngOnInit(): void {
    this.loadTenants();

    this.filterForm.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.currentPage.set(0);
      this.loadUsers();
    });
  }

  private loadTenants(): void {
    this.tenantsLoading.set(true);
    this.tenantService
      .getTenants({ page: 0, size: 200, status: 'ACTIVE' })
      .pipe(
        catchError(() => {
          this.notifications.error(this.transloco.translate('admin.tenants.errorLoad'));
          return of({ content: [], totalElements: 0, totalPages: 0, size: 200, number: 0 });
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((resp) => {
        this.tenants.set(resp.content);
        this.tenantsLoading.set(false);
        this.loadUsers();
      });
  }

  loadUsers(): void {
    this.loading.set(true);
    const { tenantId } = this.filterForm.getRawValue();

    this.adminUserService
      .getUsers({
        page: this.currentPage(),
        size: this.pageSize,
        tenantId: tenantId || undefined,
      })
      .pipe(
        catchError(() => {
          this.notifications.error(this.transloco.translate('admin.userList.errorLoad'));
          const empty: AdminPagedResponse<AdminUserResponse> = {
            content: [],
            page: 0,
            size: this.pageSize,
            totalElements: 0,
            totalPages: 0,
            first: true,
            last: true,
          };
          return of(empty);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((response) => {
        this.users.set(response.content);
        this.totalElements.set(response.totalElements);
        this.totalPages.set(response.totalPages);
        this.loading.set(false);
      });
  }

  getTenantName(tenantId: string | null): string {
    if (!tenantId) return '';
    return this.tenants().find((t) => t.id === tenantId)?.name ?? tenantId;
  }

  /**
   * True gdy `user` to konto, na którym jest aktualnie zalogowany wywołujący.
   * Używane do zablokowania edycji/usunięcia własnego konta z tego panelu –
   * zapobiega przypadkowej samo-blokadzie dostępu (np. dezaktywacja lub
   * usunięcie jedynego SUPER_ADMIN, na którym akurat pracujemy).
   */
  isSelf(user: AdminUserResponse): boolean {
    return user.id === this.auth.currentUserId();
  }

  // ── Tworzenie ─────────────────────────────────────────────────────────────

  openCreateModal(): void {
    this.editingUser.set(null);
    this.showFormModal.set(true);
  }

  // ── Edycja ────────────────────────────────────────────────────────────────

  openEditModal(user: AdminUserResponse): void {
    if (this.isSelf(user)) return;
    this.editingUser.set(user);
    this.showFormModal.set(true);
  }

  closeFormModal(): void {
    this.showFormModal.set(false);
    this.editingUser.set(null);
  }

  onFormSaved(): void {
    this.closeFormModal();
    this.loadUsers();
  }

  // ── Usunięcie ─────────────────────────────────────────────────────────────

  openDeleteModal(user: AdminUserResponse): void {
    if (this.isSelf(user)) return;
    this.deletingUser.set(user);
    this.showDeleteModal.set(true);
  }

  closeDeleteModal(): void {
    this.showDeleteModal.set(false);
    this.deletingUser.set(null);
  }

  confirmDelete(): void {
    const user = this.deletingUser();
    if (!user || this.deleteSubmitting()) return;

    this.deleteSubmitting.set(true);
    this.adminUserService
      .deleteUser(user.id)
      .pipe(
        catchError(() => {
          this.deleteSubmitting.set(false);
          this.notifications.error(this.transloco.translate('admin.userList.errorDelete'));
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.deleteSubmitting.set(false);
        this.notifications.success(this.transloco.translate('admin.userList.successDelete'));
        this.closeDeleteModal();
        this.loadUsers();
      });
  }

  // ── Wymuszenie zmiany hasła ────────────────────────────────────────────────

  openForceResetModal(user: AdminUserResponse): void {
    this.forceResetUser.set(user);
    this.showForceResetModal.set(true);
  }

  closeForceResetModal(): void {
    this.showForceResetModal.set(false);
    this.forceResetUser.set(null);
  }

  confirmForceReset(): void {
    const user = this.forceResetUser();
    if (!user || this.forceResetSubmitting()) return;

    this.forceResetSubmitting.set(true);
    this.adminUserService
      .forcePasswordReset(user.id)
      .pipe(
        catchError(() => {
          this.forceResetSubmitting.set(false);
          this.notifications.error(this.transloco.translate('admin.userList.errorDelete'));
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.forceResetSubmitting.set(false);
        this.notifications.success(
          this.transloco.translate('supervisor.users.successResetPassword'),
        );
        this.closeForceResetModal();
        this.loadUsers();
      });
  }

  // ── Paginacja ─────────────────────────────────────────────────────────────

  onPrevPage(): void {
    if (this.currentPage() > 0) {
      this.currentPage.update((p) => p - 1);
      this.loadUsers();
    }
  }

  onNextPage(): void {
    if (this.currentPage() + 1 < this.totalPages()) {
      this.currentPage.update((p) => p + 1);
      this.loadUsers();
    }
  }

  readonly firstItemIndex = (): number => this.currentPage() * this.pageSize + 1;

  readonly lastItemIndex = (): number =>
    Math.min((this.currentPage() + 1) * this.pageSize, this.totalElements());

  trackByUserId(_index: number, user: AdminUserResponse): string {
    return user.id;
  }

  getRoleLabel(role: UserRole): string {
    switch (role) {
      case 'SUPER_ADMIN':
        return 'Administrator Główny';
      case 'ADMIN':
        return 'Admin';
      case 'SUPERVISOR':
        return 'Supervisor';
      case 'AGENT':
        return 'Agent';
      default:
        return role;
    }
  }

  getStatusLabel(status: UserStatus): string {
    switch (status) {
      case 'AVAILABLE':
        return 'Dostepny';
      case 'BUSY':
        return 'Zajety';
      case 'AFTER_CONTACT':
        return 'Po kontakcie';
      case 'BREAK':
        return 'Przerwa';
      case 'ACTIVE':
        return 'Aktywny';
      case 'INACTIVE':
        return 'Nieaktywny';
      case 'OFFLINE':
        return 'Offline';
    }
  }
}
