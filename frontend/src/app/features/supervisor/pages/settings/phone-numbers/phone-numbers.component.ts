import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  ViewChild,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { catchError, of } from 'rxjs';
import { PhoneNumberService } from '../../../services/phone-number.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { PhoneNumber, PhoneRoutingRule } from '../../../models/phone-number.model';
import { RoutingRulesComponent } from './routing-rules/routing-rules.component';

const E164_PATTERN = /^\+[1-9]\d{1,14}$/;

@Component({
  selector: 'app-phone-numbers',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, RoutingRulesComponent],
  templateUrl: './phone-numbers.component.html',
  styleUrl: './phone-numbers.component.scss',
})
export class PhoneNumbersComponent implements OnInit {
  @ViewChild('addDialogRef') private addDialogRef!: ElementRef<HTMLDialogElement>;
  @ViewChild('editDialogRef') private editDialogRef!: ElementRef<HTMLDialogElement>;
  @ViewChild('deleteDialogRef') private deleteDialogRef!: ElementRef<HTMLDialogElement>;

  private readonly phoneNumberService = inject(PhoneNumberService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly fb = inject(FormBuilder);

  readonly loading = signal(true);
  readonly loadError = signal(false);
  readonly phoneNumbers = signal<PhoneNumber[]>([]);
  readonly expandedNumberId = signal<string | null>(null);
  readonly submitting = signal(false);
  readonly deletingId = signal<string | null>(null);

  /** Per-number rule counts loaded lazily */
  readonly ruleCounts = signal<Record<string, number>>({});

  /** Number being edited */
  readonly editingNumber = signal<PhoneNumber | null>(null);

  /** Number pending delete confirmation */
  readonly pendingDeleteNumber = signal<PhoneNumber | null>(null);

  readonly addForm = this.fb.group({
    number: ['', [Validators.required, Validators.pattern(E164_PATTERN)]],
    displayName: ['', Validators.maxLength(100)],
  });

  readonly editForm = this.fb.group({
    displayName: ['', Validators.maxLength(100)],
    isActive: [true],
  });

  readonly isAddSaveDisabled = computed(() => this.submitting());
  readonly isEditSaveDisabled = computed(() => this.submitting());

  /** Returns true when the phone number has at least one routing rule */
  hasActiveRules(phoneNumberId: string): boolean {
    return (this.ruleCounts()[phoneNumberId] ?? 0) > 0;
  }

  ngOnInit(): void {
    this.loadPhoneNumbers();
  }

  private loadPhoneNumbers(): void {
    this.loading.set(true);
    this.loadError.set(false);

    this.phoneNumberService
      .listPhoneNumbers()
      .pipe(
        catchError(() => {
          this.loadError.set(true);
          this.loading.set(false);
          return of([]);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((numbers) => {
        this.phoneNumbers.set(numbers);
        this.loading.set(false);
        // Load rule counts for all numbers
        numbers.forEach((n) => this.loadRuleCount(n.phoneNumberId));
      });
  }

  private loadRuleCount(phoneNumberId: string): void {
    this.phoneNumberService
      .listRoutingRules(phoneNumberId)
      .pipe(
        catchError(() => of([])),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((rules: PhoneRoutingRule[]) => {
        this.ruleCounts.update((counts) => ({ ...counts, [phoneNumberId]: rules.length }));
      });
  }

  toggleExpand(phoneNumberId: string): void {
    this.expandedNumberId.update((id) => (id === phoneNumberId ? null : phoneNumberId));
  }

  isExpanded(phoneNumberId: string): boolean {
    return this.expandedNumberId() === phoneNumberId;
  }

  getRuleCount(phoneNumberId: string): number {
    return this.ruleCounts()[phoneNumberId] ?? 0;
  }

  onRuleCountChange(phoneNumberId: string, count: number): void {
    this.ruleCounts.update((counts) => ({ ...counts, [phoneNumberId]: count }));
  }

  // ── Add number ───────────────────────────────────────────────────────────────

  openAddDialog(): void {
    this.addForm.reset({ number: '', displayName: '' });
    this.addDialogRef.nativeElement.showModal();
  }

  closeAddDialog(): void {
    this.addDialogRef.nativeElement.close();
  }

  onAddSubmit(): void {
    this.addForm.markAllAsTouched();
    if (this.addForm.invalid || this.submitting()) return;

    const raw = this.addForm.getRawValue();
    this.submitting.set(true);

    this.phoneNumberService
      .createPhoneNumber({
        number: raw.number!.trim(),
        displayName: raw.displayName?.trim() || undefined,
      })
      .pipe(
        catchError((err: unknown) => {
          this.submitting.set(false);
          const httpErr = err as { status?: number; error?: { message?: string } };
          const msg = httpErr?.error?.message;
          if (httpErr?.status === 409) {
            this.notifications.error('Numer telefonu już istnieje w systemie.');
          } else if (httpErr?.status === 400 && msg) {
            this.notifications.error(msg);
          } else {
            this.notifications.error('Nie udało się dodać numeru. Spróbuj ponownie.');
          }
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((result) => {
        if (!result) return;
        this.submitting.set(false);
        this.phoneNumbers.update((list) => [...list, result]);
        this.ruleCounts.update((counts) => ({ ...counts, [result.phoneNumberId]: 0 }));
        this.notifications.success('Numer telefonu dodany.');
        this.addDialogRef.nativeElement.close();
      });
  }

  // ── Edit number ──────────────────────────────────────────────────────────────

  openEditDialog(number: PhoneNumber): void {
    this.editingNumber.set(number);
    this.editForm.reset({ displayName: number.displayName ?? '', isActive: number.isActive });
    this.editDialogRef.nativeElement.showModal();
  }

  closeEditDialog(): void {
    this.editDialogRef.nativeElement.close();
    this.editingNumber.set(null);
  }

  onEditSubmit(): void {
    this.editForm.markAllAsTouched();
    if (this.editForm.invalid || this.submitting()) return;

    const editing = this.editingNumber();
    if (!editing) return;

    const raw = this.editForm.getRawValue();
    this.submitting.set(true);

    this.phoneNumberService
      .updatePhoneNumber(editing.phoneNumberId, {
        displayName: raw.displayName?.trim() || undefined,
        isActive: raw.isActive ?? true,
      })
      .pipe(
        catchError((err: unknown) => {
          this.submitting.set(false);
          const httpErr = err as { status?: number; error?: { message?: string } };
          const msg = httpErr?.error?.message;
          if (httpErr?.status === 400 && msg) {
            this.notifications.error(msg);
          } else {
            this.notifications.error('Nie udało się zaktualizować numeru. Spróbuj ponownie.');
          }
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((result) => {
        if (!result) return;
        this.submitting.set(false);
        this.phoneNumbers.update((list) =>
          list.map((n) => (n.phoneNumberId === result.phoneNumberId ? result : n)),
        );
        this.notifications.success('Numer telefonu zaktualizowany.');
        this.editDialogRef.nativeElement.close();
        this.editingNumber.set(null);
      });
  }

  // ── Delete number ────────────────────────────────────────────────────────────

  openDeleteDialog(number: PhoneNumber): void {
    this.pendingDeleteNumber.set(number);
    this.deleteDialogRef.nativeElement.showModal();
  }

  closeDeleteDialog(): void {
    this.deleteDialogRef.nativeElement.close();
    this.pendingDeleteNumber.set(null);
  }

  confirmDelete(): void {
    const number = this.pendingDeleteNumber();
    if (!number || this.deletingId()) return;

    this.deletingId.set(number.phoneNumberId);

    this.phoneNumberService
      .deletePhoneNumber(number.phoneNumberId)
      .pipe(
        catchError((err: unknown) => {
          this.deletingId.set(null);
          const status = (err as { status?: number })?.status;
          if (status === 409) {
            this.notifications.error('Usuń najpierw reguły routingu tego numeru.');
          } else {
            this.notifications.error('Nie udało się usunąć numeru. Spróbuj ponownie.');
          }
          this.deleteDialogRef.nativeElement.close();
          this.pendingDeleteNumber.set(null);
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        const id = number.phoneNumberId;
        this.phoneNumbers.update((list) => list.filter((n) => n.phoneNumberId !== id));
        if (this.expandedNumberId() === id) this.expandedNumberId.set(null);
        this.notifications.success('Numer telefonu usunięty.');
        this.deletingId.set(null);
        this.deleteDialogRef.nativeElement.close();
        this.pendingDeleteNumber.set(null);
      });
  }

  get addNumberError(): string | null {
    const ctrl = this.addForm.get('number')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Numer telefonu jest wymagany.';
    if (ctrl.hasError('pattern')) return 'Numer musi być w formacie E.164, np. +48123456789.';
    return null;
  }

  protected readonly trackById = (_: number, n: PhoneNumber) => n.phoneNumberId;
}
