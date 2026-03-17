import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { debounceTime, distinctUntilChanged, catchError, of } from 'rxjs';
import { UserService } from '../../../services/user.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { UserResponse, UserStatus } from '../../../models/user.model';
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
  readonly totalElements = computed(() => this.users().length);

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
    { value: 'AVAILABLE', label: 'Dostepny' },
    { value: 'BUSY', label: 'Zajety' },
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
        catchError(() => {
          this.notifications.error('Nie udalo sie pobrac listy agentow. Sprobuj ponownie.');
          return of<UserResponse[]>([]);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((users) => {
        this.users.set(users);
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

    let hasError = false;

    this.userService
      .deleteUser(user.userId)
      .pipe(
        catchError((err) => {
          hasError = true;
          if (err?.status === 409) {
            this.notifications.warning(
              `Agent "${user.firstName} ${user.lastName}" ma aktywne kontakty i nie moze byc usuniety.`,
            );
          } else {
            this.notifications.error('Nie udalo sie usunac agenta. Sprobuj ponownie.');
          }
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        if (!hasError) {
          this.notifications.success(`Agent "${user.firstName} ${user.lastName}" zostal usuniety.`);
        }
        this.closeDeleteModal();
        this.loadUsers();
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
        catchError(() => {
          this.notifications.error('Nie udalo sie wymusic resetu hasla. Sprobuj ponownie.');
          return of(undefined);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.notifications.success(
          `Agent "${user.firstName} ${user.lastName}" bedzie musial zmienic haslo przy nastepnym logowaniu.`,
        );
        this.closeResetPasswordModal();
        this.loadUsers();
      });
  }

  trackByUserId(_index: number, user: UserResponse): string {
    return user.userId;
  }
}
