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
import {
  debounceTime,
  distinctUntilChanged,
  catchError,
  of,
  finalize,
  filter,
  timer,
  switchMap,
} from 'rxjs';
import { UserService } from '../../../services/user.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { AuthService } from '../../../../../core/services/auth.service';
import { WebSocketService } from '../../../../../core/services/websocket.service';
import { PagedResponse, UserResponse, UserStatus } from '../../../models/user.model';
import {
  AgentStatusChangedPayload,
  WsEvent,
} from '../../../../agent/models/ws-event.model';
import { UserFormComponent } from '../user-form/user-form.component';
import { UserDeleteModalComponent } from '../user-delete-modal/user-delete-modal.component';
import { UserResetPasswordModalComponent } from '../user-reset-password-modal/user-reset-password-modal.component';

/** How long (ms) the status-updated highlight animation stays on a row. */
const STATUS_FLASH_DURATION_MS = 2000;

/** Polling interval (ms) used when WebSocket is not connected. */
const POLLING_INTERVAL_MS = 30_000;

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
  private readonly auth = inject(AuthService);
  private readonly ws = inject(WebSocketService);
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

  /** Set of agentIds whose status badge should display the "updated" flash animation. */
  readonly flashingAgentIds = signal<Set<string>>(new Set());

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
    { value: 'OFFLINE', label: 'Offline' },
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
    this.initWebSocket();
    this.initPollingFallback();
  }

  /**
   * Registers the supervisor STOMP topic and listens for AGENT_STATUS_CHANGED
   * events. Updates only the affected agent row in-place without a full reload.
   */
  private initWebSocket(): void {
    const tenantId = this.auth.currentTenantId();
    if (!tenantId) return;

    const supervisorTopic = `/topic/tenant/${tenantId}/supervisor`;
    this.ws.registerTopic(supervisorTopic);

    // Unregister the topic when the component is destroyed so reconnects after
    // navigation do not resubscribe to a topic no longer needed.
    this.destroyRef.onDestroy(() => {
      this.ws.unregisterTopic(supervisorTopic);
    });

    this.ws.events$
      .pipe(
        filter((e: WsEvent) => e.eventType === 'AGENT_STATUS_CHANGED'),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((e: WsEvent) => {
        const payload = e.payload as AgentStatusChangedPayload;
        this.applyStatusUpdate(payload.agentId, payload.status as UserStatus);
      });
  }

  /**
   * Falls back to polling every 30 s while the WebSocket is disconnected or
   * in error state. Switches off automatically once WS reconnects.
   */
  private initPollingFallback(): void {
    timer(POLLING_INTERVAL_MS, POLLING_INTERVAL_MS)
      .pipe(
        filter(() => {
          const state = this.ws.connectionState();
          return state === 'DISCONNECTED' || state === 'ERROR';
        }),
        switchMap(() =>
          this.userService
            .getUsers({
              page: this.currentPage(),
              size: this.pageSize,
              status: this.filterForm.getRawValue().status ?? '',
              skill: this.filterForm.getRawValue().skill ?? '',
            })
            .pipe(catchError(() => of(null))),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((response) => {
        if (response) {
          this.users.set(response.content);
          this.totalElements.set(response.totalElements);
          this.totalPages.set(response.totalPages);
        }
      });
  }

  /**
   * Applies a status change to a single agent in the local signal state.
   * Triggers a brief CSS flash animation on the affected row's status badge.
   */
  private applyStatusUpdate(agentId: string, newStatus: UserStatus): void {
    this.users.update((list) =>
      list.map((u) => (u.id === agentId ? { ...u, status: newStatus } : u)),
    );

    // Trigger flash animation.
    this.flashingAgentIds.update((ids) => {
      const next = new Set(ids);
      next.add(agentId);
      return next;
    });

    // Clear the flash class after the animation duration.
    setTimeout(() => {
      this.flashingAgentIds.update((ids) => {
        const next = new Set(ids);
        next.delete(agentId);
        return next;
      });
    }, STATUS_FLASH_DURATION_MS);
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
      .deleteUser(user.id)
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
      .forcePasswordReset(user.id)
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
    return user.id;
  }
}
