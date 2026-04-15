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
import { SlicePipe } from '@angular/common';
import {
  ReactiveFormsModule,
  FormBuilder,
  Validators,
  AbstractControl,
  ValidationErrors,
} from '@angular/forms';
import { catchError, EMPTY } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { DialerService } from '../../../supervisor/services/dialer.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { ScheduledCallbackDto } from '../../models/callback.model';

/** Custom validator: date must be in the future */
function futureDateValidator(control: AbstractControl): ValidationErrors | null {
  if (!control.value) return null;
  const selected = new Date(control.value);
  const now = new Date();
  return selected > now ? null : { pastDate: true };
}

@Component({
  selector: 'app-reschedule-callback-modal',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, SlicePipe],
  templateUrl: './reschedule-callback-modal.component.html',
  styleUrl: './reschedule-callback-modal.component.scss',
})
export class RescheduleCallbackModalComponent implements OnInit {
  readonly callbackId = input.required<string>();
  readonly currentScheduledAt = input.required<Date>();

  readonly rescheduled = output<ScheduledCallbackDto>();
  readonly cancelled = output<void>();

  private readonly fb = inject(FormBuilder);
  private readonly dialerService = inject(DialerService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly dialogRef = viewChild.required<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly loading = signal(false);
  readonly errorMessage = signal<string | null>(null);

  readonly form = this.fb.group({
    scheduledAt: ['', [Validators.required, futureDateValidator]],
    notes: ['', [Validators.maxLength(500)]],
  });

  private readonly formStatus = toSignal(this.form.statusChanges, {
    initialValue: this.form.status,
  });
  readonly canSubmit = computed(() => this.formStatus() === 'VALID' && !this.loading());

  /** Format Date to datetime-local input value: "YYYY-MM-DDTHH:mm" */
  readonly currentScheduledAtFormatted = computed(() => {
    const d = this.currentScheduledAt();
    const pad = (n: number) => n.toString().padStart(2, '0');
    return (
      `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` +
      `T${pad(d.getHours())}:${pad(d.getMinutes())}`
    );
  });

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
    const scheduledAtIso = new Date(raw.scheduledAt!).toISOString();

    this.dialerService
      .rescheduleCallback(this.callbackId(), {
        scheduledAt: scheduledAtIso,
        notes: raw.notes?.trim() || undefined,
      })
      .pipe(
        catchError((err: HttpErrorResponse) => {
          this.loading.set(false);
          if (err.status === 409) {
            this.errorMessage.set('Oddzwonienie nie jest juz oczekujace.');
          } else if (err.status === 403) {
            this.errorMessage.set('Brak uprawnien do zmiany tego oddzwonienia.');
          } else {
            this.errorMessage.set('Wystapil blad. Sprobuj ponownie.');
          }
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((dto) => {
        this.loading.set(false);
        const newDate = new Date(dto.scheduledAt).toLocaleString('pl-PL');
        this.notifications.success(`Oddzwonienie przesunięte na ${newDate}`);
        this.dialogRef().nativeElement.close();
        this.rescheduled.emit(dto);
      });
  }
}
