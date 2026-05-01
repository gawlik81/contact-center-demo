import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
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
import { DatePipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { catchError, finalize, of } from 'rxjs';
import { CallbackService } from '../../../agent/services/callback.service';
import { UserService } from '../../services/user.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { CallbackListItem } from '../../../agent/models/callback.model';
import {
  AgentOption,
  EditCallbackModalComponent,
} from '../../components/edit-callback-modal/edit-callback-modal.component';

export type { AgentOption };

@Component({
  selector: 'app-supervisor-callbacks-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, DatePipe, ReactiveFormsModule, EditCallbackModalComponent],
  templateUrl: './supervisor-callbacks-page.component.html',
  styleUrl: './supervisor-callbacks-page.component.scss',
})
export class SupervisorCallbacksPageComponent implements OnInit {
  private readonly callbackService = inject(CallbackService);
  private readonly userService = inject(UserService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(FormBuilder);

  readonly callbacks = signal<CallbackListItem[]>([]);
  readonly total = signal<number>(0);
  readonly totalPages = signal<number>(0);
  readonly loading = signal<boolean>(false);
  readonly page = signal<number>(0);
  readonly pageSize = signal<number>(20);
  readonly agents = signal<AgentOption[]>([]);

  readonly filterForm = this.fb.group({
    status: ['PENDING' as string | null],
    agentId: [null as string | null],
    sortDir: ['ASC'],
  });

  readonly showEditModal = signal(false);
  readonly editingCallback = signal<CallbackListItem | null>(null);
  readonly showDeleteModal = signal(false);
  readonly deletingCallback = signal<CallbackListItem | null>(null);
  readonly deleteLoading = signal(false);

  readonly firstItemIndex = computed(() => this.page() * this.pageSize() + 1);
  readonly lastItemIndex = computed(() =>
    Math.min((this.page() + 1) * this.pageSize(), this.total()),
  );

  ngOnInit(): void {
    this.loadAgents();
    this.loadCallbacks();
  }

  loadAgents(): void {
    this.userService
      .getUsers({ page: 0, size: 200, role: 'AGENT' })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError(() =>
          of({
            content: [],
            totalElements: 0,
            totalPages: 0,
            page: 0,
            size: 200,
            first: true,
            last: true,
          }),
        ),
      )
      .subscribe((response) => {
        const options: AgentOption[] = response.content.map((u) => ({
          id: u.id,
          name: `${u.firstName} ${u.lastName}`.trim(),
        }));
        this.agents.set(options);
      });
  }

  loadCallbacks(): void {
    this.loading.set(true);
    const { status, agentId, sortDir } = this.filterForm.getRawValue();

    this.callbackService
      .listCallbacks({
        status: (status as 'PENDING' | 'COMPLETED' | 'CANCELLED' | 'PROCESSING' | null) || null,
        agentId: agentId || null,
        sortDir: (sortDir as 'ASC' | 'DESC') || 'ASC',
        page: this.page(),
        size: this.pageSize(),
      })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError(() => {
          this.notifications.error(this.transloco.translate('supervisor.callbacks.errorLoad'));
          return of({
            content: [],
            totalElements: 0,
            totalPages: 0,
            page: 0,
            size: this.pageSize(),
            first: true,
            last: true,
          });
        }),
        finalize(() => this.loading.set(false)),
      )
      .subscribe((response) => {
        this.callbacks.set(response.content);
        this.total.set(response.totalElements);
        this.totalPages.set(response.totalPages);
      });
  }

  onFilterChange(): void {
    this.page.set(0);
    this.loadCallbacks();
  }

  openEditModal(cb: CallbackListItem): void {
    this.editingCallback.set(cb);
    this.showEditModal.set(true);
  }

  closeEditModal(): void {
    this.showEditModal.set(false);
    this.editingCallback.set(null);
  }

  onSaved(updated: CallbackListItem): void {
    this.callbacks.update((list) =>
      list.map((cb) => (cb.callbackId === updated.callbackId ? updated : cb)),
    );
    this.closeEditModal();
  }

  openDeleteModal(cb: CallbackListItem): void {
    this.deletingCallback.set(cb);
    this.showDeleteModal.set(true);
  }

  closeDeleteModal(): void {
    this.showDeleteModal.set(false);
    this.deletingCallback.set(null);
  }

  confirmDelete(): void {
    const cb = this.deletingCallback();
    if (!cb) return;
    this.deleteLoading.set(true);

    this.callbackService
      .cancelCallback(cb.callbackId)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError(() => {
          this.notifications.error(this.transloco.translate('supervisor.callbacks.errorCancel'));
          return of(null);
        }),
        finalize(() => {
          this.deleteLoading.set(false);
          this.closeDeleteModal();
        }),
      )
      .subscribe((result) => {
        if (result !== undefined) {
          this.notifications.success(
            this.transloco.translate('supervisor.callbacks.successCancel'),
          );
          this.loadCallbacks();
        }
      });
  }

  onPrevPage(): void {
    if (this.page() > 0) {
      this.page.update((p) => p - 1);
      this.loadCallbacks();
    }
  }

  onNextPage(): void {
    if (this.page() + 1 < this.totalPages()) {
      this.page.update((p) => p + 1);
      this.loadCallbacks();
    }
  }

  getStatusLabel(status: string): string {
    return this.transloco.translate(`supervisor.callbacks.statusLabels.${status}`, {}, status);
  }

  getStatusClass(status: string): string {
    const classes: Record<string, string> = {
      PENDING: 'badge--blue',
      PROCESSING: 'badge--orange',
      COMPLETED: 'badge--green',
      CANCELLED: 'badge--grey',
    };
    return classes[status] ?? '';
  }

  getClientName(cb: CallbackListItem): string {
    const full = `${cb.firstName ?? ''} ${cb.lastName ?? ''}`.trim();
    return full || '—';
  }

  truncateNote(note: string | undefined): string {
    if (!note) return '—';
    return note.length > 60 ? note.slice(0, 60) + '…' : note;
  }

  isEditable(status: string): boolean {
    return status === 'PENDING';
  }

  trackById(_index: number, cb: CallbackListItem): string {
    return cb.callbackId;
  }
}
