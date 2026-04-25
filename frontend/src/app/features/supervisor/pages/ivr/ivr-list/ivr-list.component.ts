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
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { catchError, of } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { IvrService } from '../../../services/ivr.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { IvrResponse } from '../../../models/ivr.model';

@Component({
  selector: 'app-ivr-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  templateUrl: './ivr-list.component.html',
  styleUrl: './ivr-list.component.scss',
})
export class IvrListComponent implements OnInit {
  private readonly ivrService = inject(IvrService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(true);
  readonly ivrs = signal<IvrResponse[]>([]);
  readonly actionInProgress = signal<Set<string>>(new Set());

  readonly showCreateModal = signal(false);
  readonly newIvrName = signal('');
  readonly createInProgress = signal(false);

  readonly confirmDeleteId = signal<string | null>(null);

  readonly isEmpty = computed(() => !this.loading() && this.ivrs().length === 0);

  ngOnInit(): void {
    this.loadIvrs();
  }

  loadIvrs(): void {
    this.loading.set(true);
    this.ivrService
      .getIvrList()
      .pipe(
        catchError(() => {
          this.notifications.error('Nie udalo sie pobrac listy IVR.');
          return of([]);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((ivrs) => {
        this.ivrs.set(ivrs);
        this.loading.set(false);
      });
  }

  openEditor(ivrId: string): void {
    this.router.navigate(['/supervisor/ivr', ivrId]);
  }

  openCreateModal(): void {
    this.newIvrName.set('');
    this.showCreateModal.set(true);
  }

  closeCreateModal(): void {
    this.showCreateModal.set(false);
    this.newIvrName.set('');
  }

  onCreate(): void {
    const name = this.newIvrName().trim();
    if (!name) return;
    this.createInProgress.set(true);
    this.ivrService
      .createIvr({
        name,
        definition: {
          entry_node_id: '',
          nodes: [],
        },
      })
      .pipe(
        catchError(() => {
          this.notifications.error('Nie udalo sie utworzyc drzewa IVR.');
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((ivr) => {
        this.createInProgress.set(false);
        if (ivr) {
          this.closeCreateModal();
          this.notifications.success(`Drzewo IVR "${ivr.name}" zostalo utworzone.`);
          this.router.navigate(['/supervisor/ivr', ivr.ivr_id]);
        }
      });
  }

  onActivate(ivr: IvrResponse): void {
    const id = ivr.ivr_id;
    this.setActionInProgress(id, true);
    this.ivrService
      .activateIvr(id)
      .pipe(
        catchError(() => {
          this.notifications.error(`Nie udalo sie aktywowac IVR "${ivr.name}".`);
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((updated) => {
        this.setActionInProgress(id, false);
        if (updated) {
          this.notifications.success(`IVR "${ivr.name}" zostal aktywowany.`);
          this.ivrs.update((list) => list.map((i) => (i.ivr_id === id ? updated : i)));
        }
      });
  }

  onDeactivate(ivr: IvrResponse): void {
    const id = ivr.ivr_id;
    this.setActionInProgress(id, true);
    this.ivrService
      .deactivateIvr(id)
      .pipe(
        catchError((err: unknown) => {
          const httpErr = err as HttpErrorResponse;
          if (httpErr?.status === 409) {
            this.notifications.error(
              `Nie można deaktywować IVR "${ivr.name}" – jest przypisany do reguły routingu.`,
            );
          } else {
            this.notifications.error(`Nie udało się deaktywować IVR "${ivr.name}".`);
          }
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((updated) => {
        this.setActionInProgress(id, false);
        if (updated) {
          this.notifications.success(`IVR "${ivr.name}" został deaktywowany.`);
          this.ivrs.update((list) => list.map((i) => (i.ivr_id === id ? updated : i)));
        }
      });
  }

  requestDelete(ivrId: string): void {
    this.confirmDeleteId.set(ivrId);
  }

  cancelDelete(): void {
    this.confirmDeleteId.set(null);
  }

  confirmDelete(): void {
    const id = this.confirmDeleteId();
    if (!id) return;
    const ivr = this.ivrs().find((i) => i.ivr_id === id);
    this.confirmDeleteId.set(null);
    this.setActionInProgress(id, true);
    this.ivrService
      .deleteIvr(id)
      .pipe(
        catchError(() => {
          this.notifications.error(`Nie udalo sie usunac IVR.`);
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((result) => {
        this.setActionInProgress(id, false);
        if (result !== null || result === undefined) {
          this.notifications.success(`IVR "${ivr?.name ?? ''}" zostal usuniety.`);
          this.ivrs.update((list) => list.filter((i) => i.ivr_id !== id));
        }
      });
  }

  formatDate(dateStr: string): string {
    try {
      return new Date(dateStr).toLocaleDateString('pl-PL', {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch {
      return dateStr;
    }
  }

  trackByIvrId(_index: number, ivr: IvrResponse): string {
    return ivr.ivr_id;
  }

  private setActionInProgress(id: string, inProgress: boolean): void {
    this.actionInProgress.update((set) => {
      const next = new Set(set);
      if (inProgress) next.add(id);
      else next.delete(id);
      return next;
    });
  }
}
