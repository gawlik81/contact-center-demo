import { CommonModule } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { EMPTY, catchError } from 'rxjs';
import { DispositionToneApi } from '../../../../dispositions/models/custom-disposition.model';
import {
  CreateDispositionSetItemRequest,
  CreateDispositionSetRequest,
  DispositionSet,
  DispositionSetItem,
  UpdateDispositionSetItemRequest,
  UpdateDispositionSetRequest,
} from '../../../../dispositions/models/disposition-set.model';
import { DispositionSetService } from '../../../../dispositions/services/disposition-set.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { ConfirmDialogComponent } from '../../../../../shared/components/confirm-dialog/confirm-dialog.component';

type SetFormMode = 'create' | 'edit' | null;
type ItemFormMode = 'add' | 'edit' | null;

@Component({
  selector: 'app-disposition-sets-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, ConfirmDialogComponent],
  templateUrl: './disposition-sets-page.component.html',
  styleUrl: './disposition-sets-page.component.scss',
})
export class DispositionSetsPageComponent {
  private readonly service = inject(DispositionSetService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(FormBuilder);

  // ─── State ────────────────────────────────────────────────────────────────

  readonly setsLoading = signal(false);
  readonly itemsLoading = signal(false);
  readonly setSubmitting = signal(false);
  readonly itemSubmitting = signal(false);

  readonly sets = signal<DispositionSet[]>([]);
  readonly selectedSet = signal<DispositionSet | null>(null);
  readonly items = signal<DispositionSetItem[]>([]);

  readonly setFormMode = signal<SetFormMode>(null);
  readonly editingSetId = signal<string | null>(null);
  readonly deletingSetId = signal<string | null>(null);

  readonly itemFormMode = signal<ItemFormMode>(null);
  readonly editingItemId = signal<string | null>(null);
  readonly deletingItemId = signal<string | null>(null);

  readonly hasSelectedSet = computed(() => this.selectedSet() !== null);

  readonly deletingSet = computed(
    () => this.sets().find((s) => s.id === this.deletingSetId()) ?? null,
  );

  readonly deletingItem = computed(
    () => this.items().find((i) => i.id === this.deletingItemId()) ?? null,
  );

  readonly deleteSetMessage = computed(() => {
    const name = this.deletingSet()?.name ?? '';
    return `Czy na pewno chcesz usunąć zestaw "${name}"? Operacja jest nieodwracalna.`;
  });

  readonly deleteItemMessage = computed(() => {
    const label = this.deletingItem()?.label ?? '';
    return `Czy na pewno chcesz usunąć dyspozycję "${label}" z zestawu?`;
  });

  // ─── Tone options ──────────────────────────────────────────────────────────

  readonly toneOptions: { value: DispositionToneApi; label: string }[] = [
    { value: 'positive', label: 'Pozytywny' },
    { value: 'negative', label: 'Negatywny' },
    { value: 'neutral', label: 'Neutralny' },
    { value: 'warning', label: 'Ostrzeżenie' },
  ];

  // ─── Forms ─────────────────────────────────────────────────────────────────

  readonly setForm = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(200)]],
    description: ['', Validators.maxLength(500)],
  });

  readonly itemForm = this.fb.group({
    dispositionCode: [
      '',
      [Validators.required, Validators.pattern(/^[A-Z0-9_]+$/), Validators.maxLength(50)],
    ],
    label: ['', [Validators.required, Validators.maxLength(100)]],
    tone: ['neutral' as DispositionToneApi, Validators.required],
    ordinal: [0, [Validators.required, Validators.min(0)]],
  });

  // ─── Lifecycle ─────────────────────────────────────────────────────────────

  constructor() {
    this.loadSets();
  }

  // ─── Set operations ────────────────────────────────────────────────────────

  loadSets(): void {
    this.setsLoading.set(true);
    this.service
      .listSets()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (sets) => {
          this.sets.set(sets);
          this.setsLoading.set(false);
        },
        error: () => {
          this.notifications.error('Nie udało się załadować zestawów dyspozycji.');
          this.setsLoading.set(false);
        },
      });
  }

  selectSet(set: DispositionSet): void {
    this.selectedSet.set(set);
    this.itemFormMode.set(null);
    this.editingItemId.set(null);
    this.loadItems(set.id);
  }

  loadItems(setId: string): void {
    this.itemsLoading.set(true);
    this.service
      .listItems(setId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (items) => {
          this.items.set(items);
          this.itemsLoading.set(false);
        },
        error: () => {
          this.notifications.error('Nie udało się załadować elementów zestawu.');
          this.itemsLoading.set(false);
        },
      });
  }

  openCreateSet(): void {
    this.setForm.reset({ name: '', description: '' });
    this.editingSetId.set(null);
    this.setFormMode.set('create');
  }

  openEditSet(set: DispositionSet, event: Event): void {
    event.stopPropagation();
    this.setForm.patchValue({ name: set.name, description: set.description ?? '' });
    this.editingSetId.set(set.id);
    this.setFormMode.set('edit');
  }

  cancelSetForm(): void {
    this.setFormMode.set(null);
    this.editingSetId.set(null);
    this.setForm.reset();
  }

  submitSetForm(): void {
    this.setForm.markAllAsTouched();
    if (this.setForm.invalid || this.setSubmitting()) return;

    const raw = this.setForm.getRawValue();
    const mode = this.setFormMode();
    const editId = this.editingSetId();

    this.setSubmitting.set(true);

    if (mode === 'edit' && editId) {
      const req: UpdateDispositionSetRequest = {
        name: raw.name!.trim(),
        description: raw.description?.trim() || undefined,
      };
      this.service
        .updateSet(editId, req)
        .pipe(
          catchError((err: { status?: number }) => {
            if (err.status === 409) {
              this.notifications.error('Zestaw o tej nazwie już istnieje.');
            } else {
              this.notifications.error('Nie udało się zapisać zestawu.');
            }
            this.setSubmitting.set(false);
            return EMPTY;
          }),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe((updated) => {
          this.sets.update((list) => list.map((s) => (s.id === updated.id ? updated : s)));
          if (this.selectedSet()?.id === updated.id) {
            this.selectedSet.set(updated);
          }
          this.setSubmitting.set(false);
          this.cancelSetForm();
          this.notifications.success('Zestaw został zaktualizowany.');
        });
    } else {
      const req: CreateDispositionSetRequest = {
        name: raw.name!.trim(),
        description: raw.description?.trim() || undefined,
      };
      this.service
        .createSet(req)
        .pipe(
          catchError((err: { status?: number }) => {
            if (err.status === 409) {
              this.notifications.error('Zestaw o tej nazwie już istnieje.');
            } else {
              this.notifications.error('Nie udało się utworzyć zestawu.');
            }
            this.setSubmitting.set(false);
            return EMPTY;
          }),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe((created) => {
          this.sets.update((list) => [created, ...list]);
          this.setSubmitting.set(false);
          this.cancelSetForm();
          this.notifications.success('Zestaw został utworzony.');
        });
    }
  }

  confirmDeleteSet(set: DispositionSet, event: Event): void {
    event.stopPropagation();
    this.deletingSetId.set(set.id);
  }

  executeDeleteSet(): void {
    const id = this.deletingSetId();
    if (!id) return;
    this.service
      .deleteSet(id)
      .pipe(
        catchError(() => {
          this.notifications.error('Nie udało się usunąć zestawu.');
          this.deletingSetId.set(null);
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.sets.update((list) => list.filter((s) => s.id !== id));
        if (this.selectedSet()?.id === id) {
          this.selectedSet.set(null);
          this.items.set([]);
        }
        this.deletingSetId.set(null);
        this.notifications.success('Zestaw został usunięty.');
      });
  }

  // ─── Item operations ───────────────────────────────────────────────────────

  openAddItem(): void {
    this.itemForm.reset({ dispositionCode: '', label: '', tone: 'neutral', ordinal: 0 });
    this.itemForm.get('dispositionCode')!.enable();
    this.editingItemId.set(null);
    this.itemFormMode.set('add');
  }

  openEditItem(item: DispositionSetItem): void {
    this.itemForm.patchValue({
      dispositionCode: item.dispositionCode,
      label: item.label,
      tone: item.tone,
      ordinal: item.ordinal,
    });
    this.itemForm.get('dispositionCode')!.disable();
    this.editingItemId.set(item.id);
    this.itemFormMode.set('edit');
  }

  cancelItemForm(): void {
    this.itemFormMode.set(null);
    this.editingItemId.set(null);
    this.itemForm.reset();
  }

  submitItemForm(): void {
    this.itemForm.markAllAsTouched();
    if (this.itemForm.invalid || this.itemSubmitting()) return;

    const set = this.selectedSet();
    if (!set) return;

    const raw = this.itemForm.getRawValue();
    const mode = this.itemFormMode();
    const editId = this.editingItemId();

    this.itemSubmitting.set(true);

    if (mode === 'edit' && editId) {
      const req: UpdateDispositionSetItemRequest = {
        label: raw.label!.trim(),
        tone: raw.tone as DispositionToneApi,
        ordinal: raw.ordinal ?? 0,
      };
      this.service
        .updateItem(set.id, editId, req)
        .pipe(
          catchError((err: { status?: number }) => {
            if (err.status === 409) {
              this.notifications.error('Dyspozycja o tym kodzie już istnieje w zestawie.');
            } else {
              this.notifications.error('Nie udało się zapisać elementu.');
            }
            this.itemSubmitting.set(false);
            return EMPTY;
          }),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe((updated) => {
          this.items.update((list) => list.map((i) => (i.id === updated.id ? updated : i)));
          this.itemSubmitting.set(false);
          this.cancelItemForm();
          this.notifications.success('Element zestawu został zaktualizowany.');
        });
    } else {
      const req: CreateDispositionSetItemRequest = {
        dispositionCode: raw.dispositionCode!.trim(),
        label: raw.label!.trim(),
        tone: raw.tone as DispositionToneApi,
        ordinal: raw.ordinal ?? 0,
      };
      this.service
        .addItem(set.id, req)
        .pipe(
          catchError((err: { status?: number }) => {
            if (err.status === 409) {
              this.notifications.error('Dyspozycja o tym kodzie już istnieje w zestawie.');
            } else {
              this.notifications.error('Nie udało się dodać elementu.');
            }
            this.itemSubmitting.set(false);
            return EMPTY;
          }),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe((added) => {
          this.items.update((list) => [...list, added]);
          this.sets.update((list) =>
            list.map((s) => (s.id === set.id ? { ...s, itemCount: s.itemCount + 1 } : s)),
          );
          this.selectedSet.update((s) => (s ? { ...s, itemCount: s.itemCount + 1 } : s));
          this.itemSubmitting.set(false);
          this.cancelItemForm();
          this.notifications.success('Element został dodany do zestawu.');
        });
    }
  }

  confirmDeleteItem(item: DispositionSetItem): void {
    this.deletingItemId.set(item.id);
  }

  executeDeleteItem(): void {
    const id = this.deletingItemId();
    const set = this.selectedSet();
    if (!id || !set) return;
    this.service
      .removeItem(set.id, id)
      .pipe(
        catchError(() => {
          this.notifications.error('Nie udało się usunąć elementu.');
          this.deletingItemId.set(null);
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.items.update((list) => list.filter((i) => i.id !== id));
        this.sets.update((list) =>
          list.map((s) => (s.id === set.id ? { ...s, itemCount: Math.max(0, s.itemCount - 1) } : s)),
        );
        this.selectedSet.update((s) => (s ? { ...s, itemCount: Math.max(0, s.itemCount - 1) } : s));
        this.deletingItemId.set(null);
        this.notifications.success('Element został usunięty z zestawu.');
      });
  }

  // ─── Validation helpers ────────────────────────────────────────────────────

  isSetFieldInvalid(field: string): boolean {
    const ctrl = this.setForm.get(field);
    return !!(ctrl?.invalid && ctrl.touched);
  }

  isItemFieldInvalid(field: string): boolean {
    const ctrl = this.itemForm.get(field);
    return !!(ctrl?.invalid && ctrl.touched);
  }

  get codeError(): string | null {
    const ctrl = this.itemForm.get('dispositionCode')!;
    if (!ctrl.invalid || !ctrl.touched) return null;
    if (ctrl.hasError('required')) return 'Kod jest wymagany.';
    if (ctrl.hasError('pattern')) return 'Tylko wielkie litery, cyfry i podkreślnik (A-Z, 0-9, _).';
    if (ctrl.hasError('maxlength')) return 'Maksymalnie 50 znaków.';
    return null;
  }

  readonly toneLabelMap: Record<DispositionToneApi, string> = {
    positive: 'Pozytywny',
    negative: 'Negatywny',
    neutral: 'Neutralny',
    warning: 'Ostrzeżenie',
  };
}
