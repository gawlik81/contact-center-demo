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

/** Custom validator: date must be in the future */
function futureDateValidator(control: AbstractControl): ValidationErrors | null {
  if (!control.value) return null;
  const selected = new Date(control.value);
  return selected > new Date() ? null : { pastDate: true };
}

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
  @Input() prefillFirstName?: string;
  @Input() prefillLastName?: string;

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

  readonly form = this.fb.group({
    phone: ['', [Validators.required, phoneValidator]],
    firstName: [''],
    lastName: [''],
    scheduledAt: ['', [Validators.required, futureDateValidator]],
    notes: ['', [Validators.maxLength(500)]],
  });

  // form.valid is not a signal — use toSignal() so computed() can track it reactively
  private readonly _formValid = toSignal(this.form.statusChanges.pipe(map((s) => s === 'VALID')), {
    initialValue: this.form.valid,
  });

  readonly canSubmit = computed(() => this._formValid() && !this.loading());

  /** Reactive character count for the notes textarea */
  private readonly _notesValue = toSignal(
    this.form.controls.notes.valueChanges.pipe(map((v) => v ?? '')),
    { initialValue: '' },
  );
  readonly notesLength = computed(() => this._notesValue().length);
  readonly notesNearLimit = computed(() => this.notesLength() >= 400);

  /** Minimum datetime-local value = now (format: "YYYY-MM-DDTHH:mm") */
  readonly minDateTimeLocal = computed(() => {
    const now = new Date();
    const pad = (n: number) => n.toString().padStart(2, '0');
    return (
      `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}` +
      `T${pad(now.getHours())}:${pad(now.getMinutes())}`
    );
  });

  ngOnInit(): void {
    // Pre-fill form fields from @Input values
    this.form.patchValue({
      phone: this.prefillPhone ?? '',
      firstName: this.prefillFirstName ?? '',
      lastName: this.prefillLastName ?? '',
    });

    this.dialogRef().nativeElement.showModal();
  }

  protected cancel(): void {
    this.dialogRef().nativeElement.close();
    this.cancelled.emit();
  }

  protected submit(): void {
    if (!this.canSubmit()) return;
    this.errorMessage.set(null);
    this.loading.set(true);

    const raw = this.form.getRawValue();
    const req: CreateInboundCallbackRequest = {
      phone: raw.phone!.trim(),
      firstName: raw.firstName?.trim() || undefined,
      lastName: raw.lastName?.trim() || undefined,
      scheduledAt: new Date(raw.scheduledAt!).toISOString(),
      notes: raw.notes?.trim() || undefined,
    };

    this.dialerService
      .createInboundCallback(this.contactId, req)
      .pipe(
        catchError((err: HttpErrorResponse) => {
          this.loading.set(false);
          if (err.status === 404) {
            this.errorMessage.set(this.transloco.translate('agent.scheduleCallback.errorContactNotFound'));
          } else if (err.status === 403) {
            this.errorMessage.set(this.transloco.translate('agent.scheduleCallback.errorForbidden'));
          } else {
            this.errorMessage.set(this.transloco.translate('agent.scheduleCallback.errorGeneric'));
          }
          // Trigger shake animation on error banner
          this.shakeError.set(true);
          setTimeout(() => this.shakeError.set(false), 600);
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((dto) => {
        this.loading.set(false);
        const formattedDate = new Date(dto.scheduledAt).toLocaleString(this.transloco.getActiveLang());
        this.notifications.success(
          this.transloco.translate('agent.scheduleCallback.successScheduled', { date: formattedDate }),
        );
        this.dialogRef().nativeElement.close();
        this.scheduled.emit(dto);
      });
  }
}
