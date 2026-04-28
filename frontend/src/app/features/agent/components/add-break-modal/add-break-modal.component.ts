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
import { AgentCalendarService } from '../../services/agent-calendar.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { AgentBreakRequest, BreakType, CalendarBreak } from '../../models/agent-calendar.model';

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

export interface BreakTypeOption {
  value: BreakType;
  label: string;
}

@Component({
  selector: 'app-add-break-modal',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule],
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
  private readonly destroyRef = inject(DestroyRef);

  private readonly dialogRef = viewChild.required<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly confirmCancel = signal(false);

  readonly breakTypes: BreakTypeOption[] = [
    { value: 'LUNCH', label: 'Obiad' },
    { value: 'SHORT_BREAK', label: 'Krótka przerwa' },
    { value: 'TRAINING', label: 'Szkolenie' },
    { value: 'MEETING', label: 'Spotkanie' },
    { value: 'OTHER', label: 'Inna' },
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
      breakType: ['LUNCH' as BreakType, [Validators.required]],
      startDate: ['', [Validators.required]],
      startHour: ['', [Validators.required]],
      startMinute: ['', [Validators.required]],
      endDate: ['', [Validators.required]],
      endHour: ['', [Validators.required]],
      endMinute: ['', [Validators.required]],
      notes: ['', [Validators.maxLength(500)]],
    },
    { validators: endAfterStartValidator },
  );

  private readonly _formStatus = toSignal(this.form.statusChanges, {
    initialValue: this.form.status,
  });

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
    if (ctrl.hasError('required')) return 'Typ przerwy jest wymagany.';
    return null;
  }

  get startDateError(): string | null {
    const ctrl = this.form.controls.startDate;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Data rozpoczęcia jest wymagana.';
    return null;
  }

  get startTimeError(): string | null {
    const h = this.form.controls.startHour;
    const m = this.form.controls.startMinute;
    if (!h.touched && !m.touched) return null;
    if (h.hasError('required') || m.hasError('required')) return 'Godzina rozpoczęcia jest wymagana.';
    return null;
  }

  get endDateError(): string | null {
    const ctrl = this.form.controls.endDate;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Data zakończenia jest wymagana.';
    return null;
  }

  get endTimeError(): string | null {
    const h = this.form.controls.endHour;
    const m = this.form.controls.endMinute;
    if (!h.touched && !m.touched) return null;
    if (h.hasError('required') || m.hasError('required')) return 'Godzina zakończenia jest wymagana.';
    return null;
  }

  get endNotAfterStartError(): string | null {
    const endHourTouched = this.form.controls.endHour.touched;
    const endMinuteTouched = this.form.controls.endMinute.touched;
    const endDateTouched = this.form.controls.endDate.touched;
    if (!endHourTouched && !endMinuteTouched && !endDateTouched) return null;
    return this.form.hasError('endNotAfterStart')
      ? 'Czas zakończenia musi być późniejszy niż czas rozpoczęcia.'
      : null;
  }

  // ── Lifecycle ────────────────────────────────────────────────────────────────

  ngOnInit(): void {
    this.dialogRef().nativeElement.showModal();

    const existing = this.existingBreak();
    if (existing) {
      this.prefillForm(existing);
    }
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

  protected onBackdropClick(event: MouseEvent): void {
    if (event.target === this.dialogRef().nativeElement) {
      this.close();
    }
  }

  protected close(): void {
    this.dialogRef().nativeElement.close();
    this.cancelled.emit();
  }

  protected submit(): void {
    this.form.markAllAsTouched();
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
            this.errorMessage.set('Przerwa koliduje z innym zdarzeniem.');
          } else if (err.status === 403) {
            this.errorMessage.set('Brak uprawnień do tej operacji.');
          } else {
            this.errorMessage.set('Wystąpił błąd. Spróbuj ponownie.');
          }
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.loading.set(false);
        const msg = existing ? 'Przerwa zaktualizowana.' : 'Przerwa dodana do kalendarza.';
        this.notifications.success(msg);
        this.dialogRef().nativeElement.close();
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
          if (err.status === 409) {
            this.errorMessage.set('Nie można anulować przerwy w tym statusie.');
          } else {
            this.errorMessage.set('Wystąpił błąd podczas anulowania. Spróbuj ponownie.');
          }
          this.confirmCancel.set(false);
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.loading.set(false);
        this.notifications.success('Przerwa anulowana.');
        this.dialogRef().nativeElement.close();
        this.breakCancelled.emit();
      });
  }
}
