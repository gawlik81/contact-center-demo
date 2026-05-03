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
import { ReactiveFormsModule, FormBuilder } from '@angular/forms';
import { catchError, finalize, of } from 'rxjs';
import { CallbackService } from '../../services/callback.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { CallbackListItem } from '../../models/callback.model';
import { RescheduleCallbackModalComponent } from '../../components/reschedule-callback-modal/reschedule-callback-modal.component';

@Component({
  selector: 'app-agent-callbacks-page',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, DatePipe, ReactiveFormsModule, RescheduleCallbackModalComponent],
  templateUrl: './agent-callbacks-page.component.html',
  styleUrl: './agent-callbacks-page.component.scss',
})
export class AgentCallbacksPageComponent implements OnInit {
  private readonly callbackService = inject(CallbackService);
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

  readonly filterForm = this.fb.group({
    status: ['PENDING' as string | null],
    sortDir: ['ASC'],
  });

  // Edit modal state
  readonly showEditModal = signal(false);
  readonly editingCallback = signal<CallbackListItem | null>(null);

  // Delete confirm state
  readonly showDeleteModal = signal(false);
  readonly deletingCallback = signal<CallbackListItem | null>(null);
  readonly deleteLoading = signal(false);

  readonly firstItemIndex = computed(() => this.page() * this.pageSize() + 1);
  readonly lastItemIndex = computed(() =>
    Math.min((this.page() + 1) * this.pageSize(), this.total()),
  );

  ngOnInit(): void {
    this.loadCallbacks();
  }

  loadCallbacks(): void {
    this.loading.set(true);
    const { status, sortDir } = this.filterForm.getRawValue();

    this.callbackService
      .listCallbacks({
        status: (status as 'PENDING' | 'COMPLETED' | 'CANCELLED' | 'PROCESSING' | null) || null,
        sortDir: (sortDir as 'ASC' | 'DESC') || 'ASC',
        page: this.page(),
        size: this.pageSize(),
      })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError(() => {
          this.notifications.error(this.transloco.translate('agent.callbacksPage.errorLoad'));
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

  openEditModal(callback: CallbackListItem): void {
    this.editingCallback.set(callback);
    this.showEditModal.set(true);
  }

  closeEditModal(): void {
    this.showEditModal.set(false);
    this.editingCallback.set(null);
  }

  onRescheduled(): void {
    this.closeEditModal();
    this.loadCallbacks();
  }

  openDeleteModal(callback: CallbackListItem): void {
    this.deletingCallback.set(callback);
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
          this.notifications.error(this.transloco.translate('agent.callbacksPage.cancelError'));
          return of(null);
        }),
        finalize(() => {
          this.deleteLoading.set(false);
          this.closeDeleteModal();
        }),
      )
      .subscribe((result) => {
        if (result !== undefined) {
          this.notifications.success(this.transloco.translate('agent.callbacksPage.cancelSuccess'));
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
    return this.transloco.translate(`agent.callbacksPage.statusLabels.${status}`);
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

  getScheduledDate(cb: CallbackListItem): Date {
    return new Date(cb.scheduledAt);
  }

  trackById(_index: number, cb: CallbackListItem): string {
    return cb.callbackId;
  }
}
