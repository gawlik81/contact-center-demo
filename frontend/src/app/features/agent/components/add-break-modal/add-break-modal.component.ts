import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  afterNextRender,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { catchError, EMPTY, map } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { AgentCalendarService } from '../../services/agent-calendar.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { AgentBreakRequest, BreakType, CalendarBreak } from '../../models/agent-calendar.model';
import { rememberFocus } from '../../../../shared/utils/focus.util';
import { ListboxNavDirective } from '../../../../shared/directives/listbox-nav.directive';

function endAfterStartValidator(group: AbstractControl): ValidationErrors | null {
  const startDate = group.get('startDate')?.value as string;
  const startHour = group.get('startHour')?.value as string;
  const startMinute = group.get('startMinute')?.value as string;
  const endDate = group.get('endDate')?.value as string;
  const endHour = group.get('endHour')?.value as string;
  const endMinute = group.get('endMinute')?.value as string;

  if (!startDate || !startHour || !startMinute || !endDate || !endHour || !endMinute) {
    return null;
  }

  const start = new Date(`${startDate}T${startHour}:${startMinute}:00`);
  const end = new Date(`${endDate}T${endHour}:${endMinute}:00`);

  return end <= start ? { endNotAfterStart: true } : null;
}

function startInFutureValidator(group: AbstractControl): ValidationErrors | null {
  const startDate = group.get('startDate')?.value as string;
  const startHour = group.get('startHour')?.value as string;
  const startMinute = group.get('startMinute')?.value as string;

  if (!startDate || !startHour || !startMinute) return null;

  const start = new Date(`${startDate}T${startHour}:${startMinute}:00`);
  return start <= new Date() ? { startNotInFuture: true } : null;
}

/**
 * Defaults for a new break: the most common one is a short break (the `BreakType` set is the
 * backend contract, no new values are invented), starting at the next 5-minute slot that is
 * still in the future when the agent submits, lasting 15 minutes.
 */
const DEFAULT_BREAK_TYPE: BreakType = 'SHORT_BREAK';
const DEFAULT_BREAK_MINUTES = 15;
const MINUTE_STEP = 5;

export interface BreakTypeOption {
  value: BreakType;
  label: string;
}

@Component({
  selector: 'app-add-break-modal',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, TranslocoModule, ListboxNavDirective],
  templateUrl: './add-break-modal.component.html',
  styleUrl: './add-break-modal.component.scss',
})
export class AddBreakModalComponent implements OnInit {
  readonly existingBreak = input<CalendarBreak | null>(null);

  readonly saved = output<void>();
  readonly cancelled = output<void>();
  readonly breakCancelled = output<void>();

  private readonly fb = inject(FormBuilder);
  private readonly calendarService = inject(AgentCalendarService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly dialogRef = viewChild.required<ElementRef<HTMLDialogElement>>('dialogEl');
  /** Returns focus to the element that opened the dialog once it closes. */
  private readonly restoreFocus = rememberFocus();
  private readonly breakTypeTrigger = viewChild<ElementRef<HTMLButtonElement>>('breakTypeTrigger');

  constructor() {
    // The first field gets focus instead of the dialog's close button (keyboard-first flow).
    afterNextRender(() => this.breakTypeTrigger()?.nativeElement.focus());
  }

  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly confirmCancel = signal(false);
  readonly breakTypeOpen = signal(false);

  readonly breakTypes: BreakTypeOption[] = [
    { value: 'LUNCH', label: 'agent.addBreak.breakTypes.LUNCH' },
    { value: 'SHORT_BREAK', label: 'agent.addBreak.breakTypes.SHORT_BREAK' },
    { value: 'TRAINING', label: 'agent.addBreak.breakTypes.TRAINING' },
    { value: 'MEETING', label: 'agent.addBreak.breakTypes.MEETING' },
    { value: 'OTHER', label: 'agent.addBreak.breakTypes.OTHER' },
  ];

  private readonly pad = (n: number) => n.toString().padStart(2, '0');

  readonly minDate = computed(() => {
    const d = new Date();
    return `${d.getFullYear()}-${this.pad(d.getMonth() + 1)}-${this.pad(d.getDate())}`;
  });

  readonly hours = Array.from({ length: 24 }, (_, i) => this.pad(i));
  readonly minutes = ['00', '05', '10', '15', '20', '25', '30', '35', '40', '45', '50', '55'];

  readonly isEditMode = computed(() => this.existingBreak() !== null);

  readonly form = this.fb.group(
    {
      breakType: [DEFAULT_BREAK_TYPE, [Validators.required]],
      startDate: ['', [Validators.required]],
      startHour: ['', [Validators.required]],
      startMinute: ['', [Validators.required]],
      endDate: ['', [Validators.required]],
      endHour: ['', [Validators.required]],
      endMinute: ['', [Validators.required]],
      notes: ['', [Validators.maxLength(500)]],
    },
    { validators: [endAfterStartValidator, startInFutureValidator] },
  );

  private readonly _formStatus = toSignal(this.form.statusChanges, {
    initialValue: this.form.status,
  });

  private readonly _breakTypeValue = toSignal(this.form.controls.breakType.valueChanges, {
    initialValue: DEFAULT_BREAK_TYPE,
  });

  readonly breakTypeIndex = computed(() =>
    this.breakTypes.findIndex((t) => t.value === this._breakTypeValue()),
  );

  readonly currentBreakTypeLabel = computed(
    () =>
      this.breakTypes.find((t) => t.value === this._breakTypeValue())?.label ??
      'agent.addBreak.breakTypes.LUNCH',
  );

  readonly canSubmit = computed(() => this._formStatus() === 'VALID' && !this.loading());

  private readonly _notesValue = toSignal(
    this.form.controls.notes.valueChanges.pipe(map((v) => v ?? '')),
    { initialValue: '' },
  );
  readonly notesLength = computed(() => this._notesValue().length);

  // ── Error getters ────────────────────────────────────────────────────────────

  get breakTypeError(): string | null {
    const ctrl = this.form.controls.breakType;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('agent.addBreak.breakTypeRequired');
    return null;
  }

  get startDateError(): string | null {
    const ctrl = this.form.controls.startDate;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('agent.addBreak.startDateRequired');
    return null;
  }

  get startTimeError(): string | null {
    const h = this.form.controls.startHour;
    const m = this.form.controls.startMinute;
    if (!h.touched && !m.touched) return null;
    if (h.hasError('required') || m.hasError('required'))
      return this.transloco.translate('agent.addBreak.startTimeRequired');
    return null;
  }

  get endDateError(): string | null {
    const ctrl = this.form.controls.endDate;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('agent.addBreak.endDateRequired');
    return null;
  }

  get endTimeError(): string | null {
    const h = this.form.controls.endHour;
    const m = this.form.controls.endMinute;
    if (!h.touched && !m.touched) return null;
    if (h.hasError('required') || m.hasError('required'))
      return this.transloco.translate('agent.addBreak.endTimeRequired');
    return null;
  }

  get endNotAfterStartError(): string | null {
    const endHourTouched = this.form.controls.endHour.touched;
    const endMinuteTouched = this.form.controls.endMinute.touched;
    const endDateTouched = this.form.controls.endDate.touched;
    if (!endHourTouched && !endMinuteTouched && !endDateTouched) return null;
    return this.form.hasError('endNotAfterStart')
      ? this.transloco.translate('agent.addBreak.endNotAfterStart')
      : null;
  }

  get startTimeInPastError(): string | null {
    const startDateTouched = this.form.controls.startDate.touched;
    const startHourTouched = this.form.controls.startHour.touched;
    const startMinuteTouched = this.form.controls.startMinute.touched;
    if (!startDateTouched && !startHourTouched && !startMinuteTouched) return null;
    return this.form.hasError('startNotInFuture')
      ? this.transloco.translate('agent.addBreak.errorStartInPast')
      : null;
  }

  // ── Lifecycle ────────────────────────────────────────────────────────────────

  ngOnInit(): void {
    this.dialogRef().nativeElement.showModal();

    const existing = this.existingBreak();
    if (existing) {
      this.prefillForm(existing);
    } else {
      this.applyDefaults();
    }
  }

  private applyDefaults(): void {
    const stepMs = MINUTE_STEP * 60_000;
    // +2 min grace so the slot is still in the future when the agent hits "Save".
    const start = new Date(Math.ceil((Date.now() + 2 * 60_000) / stepMs) * stepMs);
    const end = new Date(start.getTime() + DEFAULT_BREAK_MINUTES * 60_000);
    const dateOf = (d: Date) =>
      `${d.getFullYear()}-${this.pad(d.getMonth() + 1)}-${this.pad(d.getDate())}`;
    this.form.patchValue({
      startDate: dateOf(start),
      startHour: this.pad(start.getHours()),
      startMinute: this.pad(start.getMinutes()),
      endDate: dateOf(end),
      endHour: this.pad(end.getHours()),
      endMinute: this.pad(end.getMinutes()),
    });
  }

  /**
   * An untouched default start slot may have slipped into the past while the dialog sat open;
   * recompute it instead of reporting an error the agent never caused.
   */
  private refreshStaleDefaults(): void {
    if (this.isEditMode()) return;
    const c = this.form.controls;
    const untouched = [c.startDate, c.startHour, c.startMinute, c.endDate, c.endHour, c.endMinute];
    if (untouched.some((ctrl) => ctrl.dirty)) return;
    this.form.updateValueAndValidity();
    if (this.form.hasError('startNotInFuture')) this.applyDefaults();
  }

  private prefillForm(br: CalendarBreak): void {
    const start = new Date(br.startTime);
    const end = new Date(br.endTime);

    const rawStartMin = start.getMinutes();
    const roundedStartMin = Math.min(55, Math.round(rawStartMin / 5) * 5);
    const rawEndMin = end.getMinutes();
    const roundedEndMin = Math.min(55, Math.round(rawEndMin / 5) * 5);

    this.form.patchValue({
      breakType: br.breakType,
      startDate: `${start.getFullYear()}-${this.pad(start.getMonth() + 1)}-${this.pad(start.getDate())}`,
      startHour: this.pad(start.getHours()),
      startMinute: this.pad(roundedStartMin === 60 ? 55 : roundedStartMin),
      endDate: `${end.getFullYear()}-${this.pad(end.getMonth() + 1)}-${this.pad(end.getDate())}`,
      endHour: this.pad(end.getHours()),
      endMinute: this.pad(roundedEndMin === 60 ? 55 : roundedEndMin),
      notes: br.notes ?? '',
    });
  }

  // ── UI actions ───────────────────────────────────────────────────────────────

  protected selectBreakType(value: BreakType): void {
    this.form.controls.breakType.setValue(value);
    this.form.controls.breakType.markAsDirty();
    this.breakTypeOpen.set(false);
    // Mouse selection moves focus off the trigger; keep the keyboard flow on the field.
    this.breakTypeTrigger()?.nativeElement.focus();
  }

  protected onBackdropClick(event: MouseEvent): void {
    if (event.target === this.dialogRef().nativeElement) {
      this.close();
    }
  }

  /**
   * Native Esc fires `cancel` and would close the <dialog> behind the component's back;
   * route it through the same path as the Cancel button so state and outputs stay in sync.
   */
  protected onNativeCancel(event: Event): void {
    event.preventDefault();
    // An open type list absorbs Esc first (the listbox already stops it; this covers the
    // browser's own close request path).
    if (this.breakTypeOpen()) {
      this.breakTypeOpen.set(false);
      return;
    }
    this.close();
  }

  protected close(): void {
    this.dialogRef().nativeElement.close();
    this.restoreFocus();
    this.cancelled.emit();
  }

  protected submit(): void {
    this.refreshStaleDefaults();
    this.form.markAllAsTouched();
    // The "start in the future" rule depends on the clock, not only on field values.
    this.form.updateValueAndValidity();
    if (!this.canSubmit()) return;

    const raw = this.form.getRawValue();
    const startTime = new Date(
      `${raw.startDate}T${raw.startHour}:${raw.startMinute}:00`,
    ).toISOString();
    const endTime = new Date(`${raw.endDate}T${raw.endHour}:${raw.endMinute}:00`).toISOString();

    const req: AgentBreakRequest = {
      startTime,
      endTime,
      breakType: raw.breakType as BreakType,
      notes: raw.notes?.trim() || undefined,
    };

    this.errorMessage.set(null);
    this.loading.set(true);

    const existing = this.existingBreak();
    const request$ = existing
      ? this.calendarService.updateBreak(existing.id, req)
      : this.calendarService.addBreak(req);

    request$
      .pipe(
        catchError((err: HttpErrorResponse) => {
          this.loading.set(false);
          if (err.status === 409) {
            this.errorMessage.set(this.transloco.translate('agent.addBreak.errorConflict'));
          } else if (err.status === 403) {
            this.errorMessage.set(this.transloco.translate('common.errorForbidden'));
          } else {
            const detail = err.error?.detail as string | undefined;
            this.errorMessage.set(detail ?? this.transloco.translate('common.error'));
          }
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.loading.set(false);
        const msg = existing
          ? this.transloco.translate('common.success')
          : this.transloco.translate('common.success');
        this.notifications.success(msg);
        this.dialogRef().nativeElement.close();
        this.restoreFocus();
        this.saved.emit();
      });
  }

  protected cancelBreak(): void {
    this.confirmCancel.set(true);
  }

  protected abortCancelBreak(): void {
    this.confirmCancel.set(false);
  }

  protected confirmCancelBreak(): void {
    const existing = this.existingBreak();
    if (!existing) return;

    this.loading.set(true);
    this.calendarService
      .cancelBreak(existing.id)
      .pipe(
        catchError((err: HttpErrorResponse) => {
          this.loading.set(false);
          const detail = err.error?.detail as string | undefined;
          this.errorMessage.set(detail ?? this.transloco.translate('common.error'));
          this.confirmCancel.set(false);
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.loading.set(false);
        this.notifications.success(this.transloco.translate('common.success'));
        this.dialogRef().nativeElement.close();
        this.restoreFocus();
        this.breakCancelled.emit();
      });
  }
}
