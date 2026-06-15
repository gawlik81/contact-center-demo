import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { EMPTY, catchError } from 'rxjs';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  CreateCustomDispositionRequest,
  CustomDisposition,
  DispositionToneApi,
  UpdateCustomDispositionRequest,
} from '../../../features/dispositions/models/custom-disposition.model';
import { CustomDispositionService } from '../../../features/dispositions/services/custom-disposition.service';
import { NotificationService } from '../../../core/services/notification.service';
import { ConfirmDialogComponent } from '../confirm-dialog/confirm-dialog.component';
import { DispositionSet } from '../../../features/dispositions/models/disposition-set.model';
import { DispositionSetService } from '../../../features/dispositions/services/disposition-set.service';

@Component({
  selector: 'app-disposition-list-editor',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [ReactiveFormsModule, ConfirmDialogComponent, TranslocoModule],
  templateUrl: './disposition-list-editor.component.html',
  styleUrl: './disposition-list-editor.component.scss',
})
export class DispositionListEditorComponent {
  readonly campaignId = input<string | undefined>(undefined);
  readonly queueId = input<string | undefined>(undefined);

  readonly dispositions = signal<CustomDisposition[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly showForm = signal(false);
  readonly editingId = signal<string | null>(null);
  readonly deletingId = signal<string | null>(null);
  readonly submitting = signal(false);

  readonly showSetPicker = signal(false);
  readonly availableSets = signal<DispositionSet[]>([]);
  readonly setsLoading = signal(false);
  readonly applyingSet = signal(false);
  readonly pendingSet = signal<DispositionSet | null>(null);

  readonly toneOptions: { value: DispositionToneApi; key: string }[] = [
    { value: 'positive', key: 'common.tones.positive' },
    { value: 'negative', key: 'common.tones.negative' },
    { value: 'neutral', key: 'common.tones.neutral' },
    { value: 'warning', key: 'common.tones.warning' },
  ];

  private readonly fb = inject(FormBuilder);
  private readonly service = inject(CustomDispositionService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly dispositionSetService = inject(DispositionSetService);
  private readonly transloco = inject(TranslocoService);

  private readonly loadEffect = effect(() => {
    // Track input signals to react to changes
    this.campaignId();
    this.queueId();
    untracked(() => this.loadDispositions());
  });

  readonly form = this.fb.group({
    dispositionCode: [
      '',
      [Validators.required, Validators.pattern(/^[A-Z0-9_]+$/), Validators.maxLength(50)],
    ],
    label: ['', [Validators.required, Validators.maxLength(100)]],
    tone: ['neutral' as DispositionToneApi, Validators.required],
    ordinal: [0],
    isActive: [true],
  });

  loadDispositions(): void {
    const campaignId = this.campaignId();
    const queueId = this.queueId();

    if (!campaignId && !queueId) {
      return;
    }

    this.loading.set(true);
    this.error.set(null);

    const list$ = campaignId
      ? this.service.listForCampaign(campaignId)
      : this.service.listForQueue(queueId!);

    list$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (items) => {
        this.dispositions.set(items);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(this.transloco.translate('supervisor.dispositionEditor.errorLoad'));
        this.loading.set(false);
      },
    });
  }

  onAdd(): void {
    this.form.reset({
      dispositionCode: '',
      label: '',
      tone: 'neutral',
      ordinal: 0,
      isActive: true,
    });
    this.form.get('dispositionCode')!.enable();
    this.editingId.set(null);
    this.showForm.set(true);
  }

  onEdit(d: CustomDisposition): void {
    this.form.patchValue({
      dispositionCode: d.dispositionCode,
      label: d.label,
      tone: d.tone,
      ordinal: d.ordinal,
      isActive: d.isActive,
    });
    this.form.get('dispositionCode')!.disable();
    this.editingId.set(d.id);
    this.showForm.set(true);
  }

  onSubmit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.submitting()) return;

    const campaignId = this.campaignId();
    const queueId = this.queueId();
    if (!campaignId && !queueId) {
      console.warn('[DispositionListEditor] Neither campaignId nor queueId provided');
      return;
    }

    this.submitting.set(true);

    const raw = this.form.getRawValue();
    const editId = this.editingId();

    let op$;

    if (editId) {
      const req: UpdateCustomDispositionRequest = {
        label: raw.label!,
        tone: raw.tone as DispositionToneApi,
        ordinal: raw.ordinal ?? 0,
        isActive: raw.isActive ?? true,
      };
      op$ = campaignId
        ? this.service.updateForCampaign(campaignId, editId, req)
        : this.service.updateForQueue(queueId as string, editId, req);
    } else {
      const req: CreateCustomDispositionRequest = {
        dispositionCode: raw.dispositionCode!,
        label: raw.label!,
        tone: raw.tone as DispositionToneApi,
        ordinal: raw.ordinal ?? 0,
      };
      op$ = campaignId
        ? this.service.createForCampaign(campaignId, req)
        : this.service.createForQueue(queueId as string, req);
    }

    op$
      .pipe(
        catchError((err: { status?: number }) => {
          if (err.status === 409) {
            this.notifications.error(
              this.transloco.translate('supervisor.dispositionEditor.errorSaveDuplicate'),
            );
          } else {
            this.notifications.error(
              this.transloco.translate('supervisor.dispositionEditor.errorSave'),
            );
          }
          this.submitting.set(false);
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.submitting.set(false);
        this.showForm.set(false);
        this.editingId.set(null);
        this.loadDispositions();
      });
  }

  onDeleteConfirm(id: string): void {
    this.deletingId.set(id);
  }

  onDeleteExecute(): void {
    const id = this.deletingId();
    if (!id) return;

    const campaignId = this.campaignId();
    const queueId = this.queueId();
    if (!campaignId && !queueId) {
      console.warn('[DispositionListEditor] Neither campaignId nor queueId provided');
      return;
    }

    const delete$ = campaignId
      ? this.service.deleteFromCampaign(campaignId, id)
      : this.service.deleteFromQueue(queueId as string, id);

    delete$
      .pipe(
        catchError(() => {
          this.notifications.error(
            this.transloco.translate('supervisor.dispositionEditor.errorDelete'),
          );
          this.deletingId.set(null);
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.deletingId.set(null);
        this.loadDispositions();
      });
  }

  onCancel(): void {
    this.showForm.set(false);
    this.editingId.set(null);
  }

  toggleSetPicker(): void {
    if (!this.showSetPicker() && this.availableSets().length === 0) {
      this.setsLoading.set(true);
      this.dispositionSetService
        .listSets()
        .pipe(
          catchError(() => {
            this.notifications.error(
              this.transloco.translate('supervisor.dispositionEditor.errorLoadSets'),
            );
            this.setsLoading.set(false);
            return EMPTY;
          }),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe((sets) => {
          this.availableSets.set(sets);
          this.setsLoading.set(false);
        });
    }
    this.showSetPicker.update((v) => !v);
  }

  onSetSelected(set: DispositionSet): void {
    this.showSetPicker.set(false);
    this.pendingSet.set(set);
  }

  onApplySetConfirm(): void {
    const set = this.pendingSet();
    if (!set) return;

    const campaignId = this.campaignId();
    const queueId = this.queueId();
    if (!campaignId && !queueId) return;

    this.applyingSet.set(true);

    const apply$ = campaignId
      ? this.dispositionSetService.applyToCampaign(set.id, campaignId)
      : this.dispositionSetService.applyToQueue(set.id, queueId as string);

    apply$
      .pipe(
        catchError(() => {
          this.notifications.error(
            this.transloco.translate('supervisor.dispositionEditor.errorApplySet'),
          );
          this.applyingSet.set(false);
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((response) => {
        this.notifications.success(response.message);
        this.applyingSet.set(false);
        this.pendingSet.set(null);
        this.loadDispositions();
      });
  }

  onApplySetCancel(): void {
    this.pendingSet.set(null);
  }

  get codeError(): string | null {
    const ctrl = this.form.get('dispositionCode')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('supervisor.dispositionEditor.codeRequired');
    if (ctrl.hasError('pattern'))
      return this.transloco.translate('supervisor.dispositionEditor.codePattern');
    if (ctrl.hasError('maxlength'))
      return this.transloco.translate('supervisor.dispositionEditor.codeMaxLength');
    return null;
  }

  get labelError(): string | null {
    const ctrl = this.form.get('label')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('supervisor.dispositionEditor.labelRequired');
    if (ctrl.hasError('maxlength'))
      return this.transloco.translate('supervisor.dispositionEditor.labelMaxLength');
    return null;
  }
}
