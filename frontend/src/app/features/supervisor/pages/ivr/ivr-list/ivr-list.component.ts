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
  imports: [TranslocoModule, FormsModule],
  templateUrl: './ivr-list.component.html',
  styleUrl: './ivr-list.component.scss',
})
export class IvrListComponent implements OnInit {
  private readonly ivrService = inject(IvrService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
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
          this.notifications.error(this.transloco.translate('supervisor.ivr.errorLoad'));
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
          this.notifications.error(this.transloco.translate('supervisor.ivr.errorCreate'));
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((ivr) => {
        this.createInProgress.set(false);
        if (ivr) {
          this.closeCreateModal();
          this.notifications.success(this.transloco.translate('supervisor.ivr.successCreate'));
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
          this.notifications.error(this.transloco.translate('supervisor.ivr.errorActivate'));
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((updated) => {
        this.setActionInProgress(id, false);
        if (updated) {
          this.notifications.success(this.transloco.translate('supervisor.ivr.successActivate'));
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
            this.notifications.error(this.transloco.translate('supervisor.ivr.errorActivate'));
          } else {
            this.notifications.error(this.transloco.translate('supervisor.ivr.errorActivate'));
          }
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((updated) => {
        this.setActionInProgress(id, false);
        if (updated) {
          this.notifications.success(this.transloco.translate('supervisor.ivr.successActivate'));
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
    this.confirmDeleteId.set(null);
    this.setActionInProgress(id, true);
    this.ivrService
      .deleteIvr(id)
      .pipe(
        catchError(() => {
          this.notifications.error(this.transloco.translate('supervisor.ivr.errorDelete'));
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((result) => {
        this.setActionInProgress(id, false);
        if (result !== null || result === undefined) {
          this.notifications.success(this.transloco.translate('supervisor.ivr.successDelete'));
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
