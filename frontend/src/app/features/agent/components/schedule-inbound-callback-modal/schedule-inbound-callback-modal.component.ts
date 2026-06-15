import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  Input,
  OnInit,
  Output,
  EventEmitter,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import {
  ReactiveFormsModule,
  FormBuilder,
  Validators,
  AbstractControl,
  ValidationErrors,
} from '@angular/forms';
import { map } from 'rxjs';
import { catchError, EMPTY } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { DialerService } from '../../../supervisor/services/dialer.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { CreateInboundCallbackRequest, ScheduledCallbackDto } from '../../models/callback.model';

/** Custom validator: phone number – E.164 or local format */
function phoneValidator(control: AbstractControl): ValidationErrors | null {
  if (!control.value) return null;
  const phoneRegex = /^\+?[\d\s\-()]{7,20}$/;
  return phoneRegex.test(control.value.trim()) ? null : { invalidPhone: true };
}

@Component({
  selector: 'app-schedule-inbound-callback-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [ReactiveFormsModule, TranslocoModule],
  templateUrl: './schedule-inbound-callback-modal.component.html',
  styleUrl: './schedule-inbound-callback-modal.component.scss',
})
export class ScheduleInboundCallbackModalComponent implements OnInit {
  @Input() contactId!: string;
  @Input() prefillPhone?: string;

  @Output() scheduled = new EventEmitter<ScheduledCallbackDto>();
  @Output() cancelled = new EventEmitter<void>();

  private readonly fb = inject(FormBuilder);
  private readonly dialerService = inject(DialerService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly transloco = inject(TranslocoService);

  private readonly dialogRef = viewChild.required<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);
  readonly shakeError = signal(false);

  private readonly pad = (n: number) => n.toString().padStart(2, '0');

  /** Today's date in YYYY-MM-DD — used as [min] on the date input */
  readonly minDate = computed(() => {
    const d = new Date();
    return `${d.getFullYear()}-${this.pad(d.getMonth() + 1)}-${this.pad(d.getDate())}`;
  });

  readonly hours = Array.from({ length: 24 }, (_, i) => i.toString().padStart(2, '0'));
  readonly minutes = ['00', '05', '10', '15', '20', '25', '30', '35', '40', '45', '50', '55'];

  readonly form = this.fb.group({
    phone: ['', [Validators.required, phoneValidator]],
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

  /** Reactive character count for the notes textarea */
  private readonly _notesValue = toSignal(
    this.form.controls.notes.valueChanges.pipe(map((v) => v ?? '')),
    { initialValue: '' },
  );
  readonly notesLength = computed(() => this._notesValue().length);

  // ── Field error getters ───────────────────────────────────────────────────────

  get scheduledDateError(): string | null {
    const ctrl = this.form.controls.scheduledDate;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('agent.scheduleCallback.dateRequired');
    return null;
  }

  get scheduledTimeError(): string | null {
    const h = this.form.controls.scheduledHour;
    const m = this.form.controls.scheduledMinute;
    const touched = h.touched || m.touched;
    if (!touched) return null;
    if (h.hasError('required') || m.hasError('required'))
      return this.transloco.translate('agent.scheduleCallback.timeRequired');
    return null;
  }

  ngOnInit(): void {
    // Pre-fill phone from @Input
    this.form.patchValue({ phone: this.prefillPhone ?? '' });

    // Pre-fill date/time: now + 10 min, rounded to next 5-minute slot
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

    this.dialogRef().nativeElement.showModal();
  }

  protected cancel(): void {
    this.dialogRef().nativeElement.close();
    this.cancelled.emit();
  }

  protected submit(): void {
    this.form.markAllAsTouched();
    if (!this.canSubmit()) return;

    this.errorMessage.set(null);
    this.loading.set(true);

    const raw = this.form.getRawValue();
    const scheduledAt = new Date(
      `${raw.scheduledDate}T${raw.scheduledHour}:${raw.scheduledMinute}`,
    ).toISOString();

    const req: CreateInboundCallbackRequest = {
      phone: raw.phone!.trim(),
      scheduledAt,
      notes: raw.notes?.trim() || undefined,
    };

    this.dialerService
      .createInboundCallback(this.contactId, req)
      .pipe(
        catchError((err: HttpErrorResponse) => {
          this.loading.set(false);
          if (err.status === 404) {
            this.errorMessage.set(
              this.transloco.translate('agent.scheduleCallback.errorContactNotFound'),
            );
          } else if (err.status === 403) {
            this.errorMessage.set(
              this.transloco.translate('agent.scheduleCallback.errorForbidden'),
            );
          } else {
            this.errorMessage.set(this.transloco.translate('agent.scheduleCallback.errorGeneric'));
          }
          this.shakeError.set(true);
          setTimeout(() => this.shakeError.set(false), 600);
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((dto) => {
        this.loading.set(false);
        const formattedDate = new Date(dto.scheduledAt).toLocaleString(
          this.transloco.getActiveLang(),
        );
        this.notifications.success(
          this.transloco.translate('agent.scheduleCallback.successScheduled', {
            date: formattedDate,
          }),
        );
        this.dialogRef().nativeElement.close();
        this.scheduled.emit(dto);
      });
  }
}
