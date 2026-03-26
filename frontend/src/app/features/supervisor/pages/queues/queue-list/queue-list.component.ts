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
  BehaviorSubject,
  catchError,
  debounceTime,
  distinctUntilChanged,
  finalize,
  of,
  switchMap,
} from 'rxjs';
import { QueueService } from '../../../services/queue.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { Queue } from '../../../models/queue.model';
import { QueueFormComponent } from '../queue-form/queue-form.component';
import { QueueDeleteModalComponent } from '../queue-delete-modal/queue-delete-modal.component';

@Component({
  selector: 'app-queue-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, QueueFormComponent, QueueDeleteModalComponent],
  templateUrl: './queue-list.component.html',
  styleUrl: './queue-list.component.scss',
})
export class QueueListComponent implements OnInit {
  private readonly queueService = inject(QueueService);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(false);
  readonly toggling = signal<string | null>(null);
  readonly deleting = signal(false);
  readonly queues = signal<Queue[]>([]);
  readonly totalElements = signal(0);
  readonly totalPages = signal(0);
  readonly currentPage = signal(0);
  readonly pageSize = 20;

  readonly selectedQueue = signal<Queue | null>(null);
  readonly showFormModal = signal(false);
  readonly isEditMode = signal(false);
  readonly showDeleteModal = signal(false);

  readonly searchForm = this.fb.group({
    name: [''],
  });

  private readonly loadTrigger$ = new BehaviorSubject<void>(undefined);

  ngOnInit(): void {
    this.loadTrigger$
      .pipe(
        switchMap(() => {
          this.loading.set(true);
          const name = this.searchForm.getRawValue().name ?? '';
          return this.queueService.getQueues(this.currentPage(), this.pageSize, name).pipe(
            catchError(() => {
              this.notifications.error('Nie udalo sie pobrac listy kolejek. Sprobuj ponownie.');
              return of({ content: [], totalElements: 0, totalPages: 0, page: 0, size: this.pageSize, first: true, last: true });
            }),
            finalize(() => this.loading.set(false)),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((response) => {
        this.queues.set(response.content);
        this.totalElements.set(response.totalElements);
        this.totalPages.set(response.totalPages);
      });

    this.searchForm.controls.name.valueChanges
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.currentPage.set(0);
        this.loadQueues();
      });
  }

  loadQueues(): void {
    this.loadTrigger$.next();
  }

  openCreateModal(): void {
    this.selectedQueue.set(null);
    this.isEditMode.set(false);
    this.showFormModal.set(true);
  }

  openEditModal(queue: Queue): void {
    this.selectedQueue.set(queue);
    this.isEditMode.set(true);
    this.showFormModal.set(true);
  }

  closeFormModal(): void {
    this.showFormModal.set(false);
    this.selectedQueue.set(null);
  }

  onFormSaved(): void {
    this.closeFormModal();
    this.loadQueues();
  }

  openDeleteModal(queue: Queue): void {
    this.selectedQueue.set(queue);
    this.showDeleteModal.set(true);
  }

  closeDeleteModal(): void {
    this.showDeleteModal.set(false);
    this.selectedQueue.set(null);
  }

  onDeleteConfirmed(): void {
    const queue = this.selectedQueue();
    if (!queue) return;

    this.deleting.set(true);

    this.queueService
      .deleteQueue(queue.queueId)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError((err: { status?: number }) => {
          if (err?.status === 409) {
            this.notifications.error(
              `Nie mozna usunac kolejki "${queue.name}" — posiada aktywne kontakty.`,
            );
          } else {
            this.notifications.error('Nie udalo sie usunac kolejki. Sprobuj ponownie.');
          }
          return of(null);
        }),
        finalize(() => this.deleting.set(false)),
      )
      .subscribe((result) => {
        if (result !== null) {
          this.notifications.success(`Kolejka "${queue.name}" zostala usunieta.`);
          this.closeDeleteModal();
          this.loadQueues();
        }
      });
  }

  toggleActive(queue: Queue): void {
    this.toggling.set(queue.queueId);

    this.queueService
      .updateQueue(queue.queueId, { active: !queue.active })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError(() => {
          this.notifications.error('Nie udalo sie zmienic statusu kolejki. Sprobuj ponownie.');
          return of(null);
        }),
        finalize(() => this.toggling.set(null)),
      )
      .subscribe((updated) => {
        if (updated) {
          const label = updated.active ? 'aktywowana' : 'dezaktywowana';
          this.notifications.success(`Kolejka "${updated.name}" zostala ${label}.`);
          this.loadQueues();
        }
      });
  }

  onPrevPage(): void {
    if (this.currentPage() > 0) {
      this.currentPage.update((p) => p - 1);
      this.loadQueues();
    }
  }

  onNextPage(): void {
    if (this.currentPage() + 1 < this.totalPages()) {
      this.currentPage.update((p) => p + 1);
      this.loadQueues();
    }
  }

  readonly firstItemIndex = (): number => this.currentPage() * this.pageSize + 1;
  readonly lastItemIndex = (): number =>
    Math.min((this.currentPage() + 1) * this.pageSize, this.totalElements());

  trackByQueueId(_index: number, queue: Queue): string {
    return queue.queueId;
  }
}
