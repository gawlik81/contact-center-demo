import { TranslocoModule } from '@jsverse/transloco';
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
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { catchError, EMPTY, map } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { DialerService } from '../../../supervisor/services/dialer.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { ScheduledCallbackDto } from '../../models/callback.model';

@Component({
  selector: 'app-reschedule-callback-modal',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, TranslocoModule],
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

  private readonly pad = (n: number) => n.toString().padStart(2, '0');

  readonly minDate = computed(() => {
    const d = new Date();
    return `${d.getFullYear()}-${this.pad(d.getMonth() + 1)}-${this.pad(d.getDate())}`;
  });

  readonly hours = Array.from({ length: 24 }, (_, i) => this.pad(i));
  readonly minutes = ['00', '05', '10', '15', '20', '25', '30', '35', '40', '45', '50', '55'];

  readonly form = this.fb.group({
    scheduledDate: ['', [Validators.required]],
    scheduledHour: ['', [Validators.required]],
    scheduledMinute: ['', [Validators.required]],
    notes: ['', [Validators.maxLength(500)]],
  });

  private readonly _formStatus = toSignal(this.form.statusChanges, {
    initialValue: this.form.status,
  });
  readonly canSubmit = computed(() => this._formStatus() === 'VALID' && !this.loading());

  private readonly _scheduledDateValue = toSignal(
    this.form.controls.scheduledDate.valueChanges,
    { initialValue: this.form.controls.scheduledDate.value },
  );

  readonly isToday = computed(() => {
    const val = this._scheduledDateValue();
    return !!val && val === this.minDate();
  });

  readonly minHourToday = computed(() => {
    const future = new Date(Date.now() + 5 * 60 * 1000);
    return future.getHours();
  });

  private readonly _notesValue = toSignal(
    this.form.controls.notes.valueChanges.pipe(map((v) => v ?? '')),
    { initialValue: '' },
  );
  readonly notesLength = computed(() => this._notesValue().length);

  readonly currentScheduledAtFormatted = computed(() => {
    const d = this.currentScheduledAt();
    return (
      `${d.getFullYear()}-${this.pad(d.getMonth() + 1)}-${this.pad(d.getDate())}` +
      ` ${this.pad(d.getHours())}:${this.pad(d.getMinutes())}`
    );
  });

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

  ngOnInit(): void {
    this.dialogRef().nativeElement.showModal();

    const d = this.currentScheduledAt();
    const scheduledDate = `${d.getFullYear()}-${this.pad(d.getMonth() + 1)}-${this.pad(d.getDate())}`;
    const scheduledHour = this.pad(d.getHours());
    const rawMinutes = d.getMinutes();
    const rounded = Math.min(55, Math.round(rawMinutes / 5) * 5);
    const scheduledMinute = this.pad(rounded === 60 ? 55 : rounded);
    this.form.patchValue({ scheduledDate, scheduledHour, scheduledMinute });
  }

  protected onBackdropClick(event: MouseEvent): void {
    if (event.target === this.dialogRef().nativeElement) {
      this.cancel();
    }
  }

  protected cancel(): void {
    this.dialogRef().nativeElement.close();
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

    this.dialerService
      .rescheduleCallback(this.callbackId(), {
        scheduledAt: combined.toISOString(),
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
