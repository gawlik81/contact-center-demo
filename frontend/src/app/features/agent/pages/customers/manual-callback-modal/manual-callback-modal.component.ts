import { TranslocoModule } from '@jsverse/transloco';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { catchError, EMPTY, map } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { ManualCallbackService } from '../../../services/manual-callback.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { CustomerSummary } from '../../../models/customer-search.model';
import { ManualCallbackResponse } from '../../../models/manual-callback.model';

/** Custom validator: E.164 phone number format */
function e164Validator(control: AbstractControl): ValidationErrors | null {
  if (!control.value) return null;
  const e164Regex = /^\+[1-9][0-9]{6,14}$/;
  return e164Regex.test(control.value.trim()) ? null : { invalidE164: true };
}

@Component({
  selector: 'app-manual-callback-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, ReactiveFormsModule],
  templateUrl: './manual-callback-modal.component.html',
  styleUrl: './manual-callback-modal.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class ManualCallbackModalComponent implements AfterViewInit {
  readonly customer = input.required<CustomerSummary>();

  readonly scheduled = output<ManualCallbackResponse>();
  readonly cancelled = output<void>();

  private readonly fb = inject(FormBuilder);
  private readonly callbackService = inject(ManualCallbackService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly loading = signal(false);
  readonly visible = signal(false);
  readonly errorMessage = signal<string | null>(null);

  /** True when the customer has at least one phone number — show select, else show text input */
  readonly hasPhones = computed(() => this.customer().phone.length > 0);

  private readonly pad = (n: number) => n.toString().padStart(2, '0');

  /** Today's date in YYYY-MM-DD — used as [min] on the date input */
  readonly minDate = computed(() => {
    const d = new Date();
    return `${d.getFullYear()}-${this.pad(d.getMonth() + 1)}-${this.pad(d.getDate())}`;
  });

  /** Minimum selectable time — only enforced when selected date equals today */
  readonly minTimeForToday = computed(() => {
    const future = new Date(Date.now() + 5 * 60 * 1000);
    return `${this.pad(future.getHours())}:${this.pad(future.getMinutes())}`;
  });

  readonly hours = Array.from({ length: 24 }, (_, i) => this.pad(i));
  readonly minutes = ['00', '05', '10', '15', '20', '25', '30', '35', '40', '45', '50', '55'];

  readonly form = this.fb.group({
    phoneNumber: ['', [Validators.required]],
    scheduledDate: ['', [Validators.required]],
    scheduledHour: ['', [Validators.required]],
    scheduledMinute: ['', [Validators.required]],
    notes: ['', [Validators.maxLength(500)]],
  });

  private readonly _formStatus = toSignal(this.form.statusChanges, {
    initialValue: this.form.status,
  });

  readonly canSubmit = computed(() => this._formStatus() === 'VALID' && !this.loading());

  private readonly _scheduledDateValue = toSignal(this.form.controls.scheduledDate.valueChanges, {
    initialValue: this.form.controls.scheduledDate.value,
  });

  /** True when the selected date is today — used to filter available hours */
  readonly isToday = computed(() => {
    const val = this._scheduledDateValue();
    return !!val && val === this.minDate();
  });

  /** Minimum hour (inclusive) when date is today */
  readonly minHourToday = computed(() => {
    const future = new Date(Date.now() + 5 * 60 * 1000);
    return future.getHours();
  });

  private readonly _notesValue = toSignal(
    this.form.controls.notes.valueChanges.pipe(map((v) => v ?? '')),
    { initialValue: '' },
  );
  readonly notesLength = computed(() => this._notesValue().length);

  readonly customerDisplayName = computed(() => {
    const c = this.customer();
    return [c.firstName, c.lastName].filter(Boolean).join(' ') || 'Klient';
  });

  // ── Field error getters (consistent with tenant-add-modal pattern) ───────────

  get phoneError(): string | null {
    const ctrl = this.form.controls.phoneNumber;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Numer telefonu jest wymagany.';
    if (ctrl.hasError('invalidE164'))
      return 'Podaj numer w formacie miedzynarodowym (np. +48123456789).';
    return null;
  }

  get scheduledDateError(): string | null {
    const ctrl = this.form.controls.scheduledDate;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Data jest wymagana.';
    return null;
  }

  get scheduledTimeError(): string | null {
    const h = this.form.controls.scheduledHour;
    const m = this.form.controls.scheduledMinute;
    const touched = h.touched || m.touched;
    if (!touched) return null;
    if (h.hasError('required') || m.hasError('required')) return 'Godzina jest wymagana.';
    return null;
  }

  get notesError(): string | null {
    const ctrl = this.form.controls.notes;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('maxlength')) return 'Notatka nie moze przekraczac 500 znakow.';
    return null;
  }

  get isSaveDisabled(): boolean {
    return this.form.invalid || this.form.pending || this.loading();
  }

  ngAfterViewInit(): void {
    if (this.hasPhones()) {
      this.form.controls.phoneNumber.patchValue(this.customer().phone[0]);
    } else {
      this.form.controls.phoneNumber.addValidators(e164Validator);
      this.form.controls.phoneNumber.updateValueAndValidity();
    }

    // Pre-fill date = tomorrow, time = next round 5-minute slot after now+10min
    const defaultDt = new Date(Date.now() + 10 * 60 * 1000);
    defaultDt.setMinutes(Math.ceil(defaultDt.getMinutes() / 5) * 5, 0, 0);
    if (defaultDt.getMinutes() === 60) {
      defaultDt.setHours(defaultDt.getHours() + 1, 0, 0, 0);
    }
    const defaultDate = `${defaultDt.getFullYear()}-${this.pad(defaultDt.getMonth() + 1)}-${this.pad(defaultDt.getDate())}`;
    const defaultHour = this.pad(defaultDt.getHours());
    const defaultMinute = this.pad(Math.floor(defaultDt.getMinutes() / 5) * 5);

    this.form.patchValue({
      scheduledDate: defaultDate,
      scheduledHour: defaultHour,
      scheduledMinute: defaultMinute,
    });

    this.open();
  }

  open(): void {
    this.visible.set(true);
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) dialog.showModal();
  }

  close(): void {
    this.visible.set(false);
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && dialog.open) dialog.close();
  }

  onEscapeKey(event: Event): void {
    if (this.visible()) {
      event.preventDefault();
      this.close();
      this.cancelled.emit();
    }
  }

  onBackdropClick(event: MouseEvent): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (event.target === dialog) {
      this.close();
      this.cancelled.emit();
    }
  }

  protected cancel(): void {
    this.close();
    this.cancelled.emit();
  }

  protected submit(): void {
    this.form.markAllAsTouched();
    if (!this.canSubmit()) return;

    const raw = this.form.getRawValue();
    const combined = new Date(`${raw.scheduledDate}T${raw.scheduledHour}:${raw.scheduledMinute}`);
    const minThreshold = new Date(Date.now() + 5 * 60 * 1000);
    if (combined < minThreshold) {
      this.errorMessage.set('Wybierz termin co najmniej 5 minut w przyszlosci.');
      return;
    }

    this.errorMessage.set(null);
    this.loading.set(true);

    this.callbackService
      .createManualCallback({
        customerId: this.customer().customerId,
        phoneNumber: raw.phoneNumber!.trim(),
        scheduledAt: combined.toISOString(),
        notes: raw.notes?.trim() || undefined,
      })
      .pipe(
        catchError((err: HttpErrorResponse) => {
          this.loading.set(false);
          if (err.status === 400) {
            const message = err.error?.message ?? err.error?.error ?? 'Nieprawidlowe dane zadania.';
            this.errorMessage.set(message);
          } else if (err.status === 403) {
            this.errorMessage.set('Brak uprawnien do zamowienia oddzwonienia.');
          } else if (err.status === 404) {
            this.errorMessage.set('Klient nie zostal znaleziony.');
          } else {
            this.errorMessage.set('Wystapil blad serwera. Sprobuj ponownie.');
          }
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((response) => {
        this.loading.set(false);
        const formattedDate = new Date(response.scheduledAt).toLocaleString('pl-PL');
        this.notifications.success(`Oddzwonienie zaplanowane na ${formattedDate}`);
        this.close();
        this.scheduled.emit(response);
      });
  }
}
