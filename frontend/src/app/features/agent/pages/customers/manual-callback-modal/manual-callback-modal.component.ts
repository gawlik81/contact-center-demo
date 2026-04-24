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

/** Custom validator: date must be at least 5 minutes in the future */
function minFutureValidator(minMinutes: number) {
  return (control: AbstractControl): ValidationErrors | null => {
    if (!control.value) return null;
    const selected = new Date(control.value);
    const threshold = new Date(Date.now() + minMinutes * 60 * 1000);
    return selected >= threshold ? null : { tooSoon: { minMinutes } };
  };
}

/** Custom validator: E.164 phone number format */
function e164Validator(control: AbstractControl): ValidationErrors | null {
  if (!control.value) return null;
  const e164Regex = /^\+[1-9][0-9]{6,14}$/;
  return e164Regex.test(control.value.trim()) ? null : { invalidE164: true };
}

@Component({
  selector: 'app-manual-callback-modal',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule],
  templateUrl: './manual-callback-modal.component.html',
  styleUrl: './manual-callback-modal.component.scss',
})
export class ManualCallbackModalComponent implements OnInit {
  readonly customer = input.required<CustomerSummary>();

  readonly scheduled = output<ManualCallbackResponse>();
  readonly cancelled = output<void>();

  private readonly fb = inject(FormBuilder);
  private readonly callbackService = inject(ManualCallbackService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly dialogRef = viewChild.required<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);

  /** True when the customer has at least one phone number → show select, else show text input */
  readonly hasPhones = computed(() => this.customer().phone.length > 0);

  /** Minimum datetime-local string = now + 5 minutes */
  readonly minDateTimeLocal = computed(() => {
    const future = new Date(Date.now() + 5 * 60 * 1000);
    const pad = (n: number) => n.toString().padStart(2, '0');
    return (
      `${future.getFullYear()}-${pad(future.getMonth() + 1)}-${pad(future.getDate())}` +
      `T${pad(future.getHours())}:${pad(future.getMinutes())}`
    );
  });

  readonly form = this.fb.group({
    phoneNumber: ['', [Validators.required]],
    scheduledAt: ['', [Validators.required, minFutureValidator(5)]],
    notes: ['', [Validators.maxLength(500)]],
  });

  private readonly _formStatus = toSignal(this.form.statusChanges, {
    initialValue: this.form.status,
  });

  readonly canSubmit = computed(() => this._formStatus() === 'VALID' && !this.loading());

  private readonly _notesValue = toSignal(
    this.form.controls.notes.valueChanges.pipe(map((v) => v ?? '')),
    { initialValue: '' },
  );
  readonly notesLength = computed(() => this._notesValue().length);

  readonly customerDisplayName = computed(() => {
    const c = this.customer();
    return [c.firstName, c.lastName].filter(Boolean).join(' ') || 'Klient';
  });

  ngOnInit(): void {
    // Pre-fill first phone if available
    if (this.hasPhones()) {
      this.form.controls.phoneNumber.patchValue(this.customer().phone[0]);
    } else {
      // Free-text input — add E.164 validator dynamically
      this.form.controls.phoneNumber.addValidators(e164Validator);
      this.form.controls.phoneNumber.updateValueAndValidity();
    }
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

    this.callbackService
      .createManualCallback({
        customerId: this.customer().customerId,
        phoneNumber: raw.phoneNumber!.trim(),
        scheduledAt: new Date(raw.scheduledAt!).toISOString(),
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
        this.dialogRef().nativeElement.close();
        this.scheduled.emit(response);
      });
  }
}
