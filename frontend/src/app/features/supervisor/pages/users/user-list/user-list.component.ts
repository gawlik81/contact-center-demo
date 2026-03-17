import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged, catchError, of, finalize } from 'rxjs';
import { UserService } from '../../../services/user.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { PagedResponse, UserResponse, UserStatus } from '../../../models/user.model';
import { UserFormComponent } from '../user-form/user-form.component';
import { UserDeleteModalComponent } from '../user-delete-modal/user-delete-modal.component';
import { UserResetPasswordModalComponent } from '../user-reset-password-modal/user-reset-password-modal.component';

@Component({
  selector: 'app-user-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule,
    UserFormComponent,
    UserDeleteModalComponent,
    UserResetPasswordModalComponent,
  ],
  templateUrl: './user-list.component.html',
  styleUrl: './user-list.component.scss',
})
export class UserListComponent implements OnInit {
  private readonly userService = inject(UserService);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(false);
  readonly users = signal<UserResponse[]>([]);
  readonly totalElements = signal(0);
  readonly totalPages = signal(0);

  readonly currentPage = signal(0);
  readonly pageSize = 20;

  readonly selectedUser = signal<UserResponse | null>(null);
  readonly showFormModal = signal(false);
  readonly showDeleteModal = signal(false);
  readonly showResetPasswordModal = signal(false);
  readonly isEditMode = signal(false);

  readonly filterForm = this.fb.group({
    skill: [''],
    status: ['' as UserStatus | ''],
  });

  readonly statusOptions: { value: UserStatus | ''; label: string }[] = [
    { value: '', label: 'Wszystkie statusy' },
    { value: 'AVAILABLE', label: 'Dostępny' },
    { value: 'BUSY', label: 'Zajęty' },
    { value: 'AFTER_CONTACT', label: 'Po kontakcie' },
    { value: 'BREAK', label: 'Przerwa' },
    { value: 'ACTIVE', label: 'Aktywny' },
    { value: 'INACTIVE', label: 'Nieaktywny' },
  ];

  ngOnInit(): void {
    this.filterForm.valueChanges
      .pipe(
        debounceTime(300),
        distinctUntilChanged((a, b) => JSON.stringify(a) === JSON.stringify(b)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.currentPage.set(0);
        this.loadUsers();
      });

    this.loadUsers();
  }

  loadUsers(): void {
    this.loading.set(true);
    const { skill, status } = this.filterForm.getRawValue();

    this.userService
      .getUsers({
        page: this.currentPage(),
        size: this.pageSize,
        status: status ?? '',
        skill: skill ?? '',
      })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError(() => {
          this.notifications.error('Nie udało się pobrać listy agentów. Spróbuj ponownie.');
          const empty: PagedResponse<UserResponse> = {
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
      )
      .subscribe((response) => {
        this.users.set(response.content);
        this.totalElements.set(response.totalElements);
        this.totalPages.set(response.totalPages);
        this.loading.set(false);
      });
  }

  openCreateModal(): void {
    this.selectedUser.set(null);
    this.isEditMode.set(false);
    this.showFormModal.set(true);
  }

  openEditModal(user: UserResponse): void {
    this.selectedUser.set(user);
    this.isEditMode.set(true);
    this.showFormModal.set(true);
  }

  closeFormModal(): void {
    this.showFormModal.set(false);
    this.selectedUser.set(null);
  }

  onFormSaved(): void {
    this.closeFormModal();
    this.loadUsers();
  }

  openDeleteModal(user: UserResponse): void {
    this.selectedUser.set(user);
    this.showDeleteModal.set(true);
  }

  closeDeleteModal(): void {
    this.showDeleteModal.set(false);
    this.selectedUser.set(null);
  }

  onDeleteConfirmed(): void {
    const user = this.selectedUser();
    if (!user) return;

    this.userService
      .deleteUser(user.userId)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError((err) => {
          if (err?.status === 409) {
            this.notifications.warning(
              `Agent "${user.firstName} ${user.lastName}" ma aktywne kontakty i nie może być usunięty.`,
            );
          } else {
            this.notifications.error('Nie udało się usunąć agenta. Spróbuj ponownie.');
          }
          return of(null);
        }),
        finalize(() => this.closeDeleteModal()),
      )
      .subscribe((result) => {
        if (result !== null) {
          this.notifications.success(`Agent "${user.firstName} ${user.lastName}" został usunięty.`);
          this.loadUsers();
        }
      });
  }

  openResetPasswordModal(user: UserResponse): void {
    this.selectedUser.set(user);
    this.showResetPasswordModal.set(true);
  }

  closeResetPasswordModal(): void {
    this.showResetPasswordModal.set(false);
    this.selectedUser.set(null);
  }

  onResetPasswordConfirmed(): void {
    const user = this.selectedUser();
    if (!user) return;

    this.userService
      .forcePasswordReset(user.userId)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError(() => {
          this.notifications.error('Nie udało się wymusić resetu hasła. Spróbuj ponownie.');
          return of(undefined);
        }),
      )
      .subscribe(() => {
        this.notifications.success(
          `Agent "${user.firstName} ${user.lastName}" będzie musiał zmienić hasło przy następnym logowaniu.`,
        );
        this.closeResetPasswordModal();
        this.loadUsers();
      });
  }

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

  trackByUserId(_index: number, user: UserResponse): string {
    return user.userId;
  }
}
