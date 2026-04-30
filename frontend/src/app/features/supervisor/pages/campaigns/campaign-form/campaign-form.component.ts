import { TranslocoModule } from '@jsverse/transloco';
import {AfterViewInit, ChangeDetectionStrategy, Component, DestroyRef, ElementRef, inject, input, OnInit, output, signal, viewChild,} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {AbstractControl, FormBuilder, ReactiveFormsModule, ValidationErrors, ValidatorFn, Validators,} from '@angular/forms';
import {catchError, of} from 'rxjs';
import {CampaignService} from '../../../services/campaign.service';
import {QueueService} from '../../../services/queue.service';
import {NotificationService} from '../../../../../core/services/notification.service';
import {ActiveDay, Campaign, CampaignSchedule, CampaignType, DialerType,} from '../../../models/campaign.model';
import {Queue} from '../../../models/queue.model';

/** Validates HH:MM time format */
const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/;

function timePatternValidator(control: AbstractControl): ValidationErrors | null {
  const val: string = control.value ?? '';
  if (!val) return null;
  return TIME_PATTERN.test(val) ? null : { timePattern: true };
}

/** Cross-field: endDate must be >= startDate */
function endDateValidator(): ValidatorFn {
  return (group: AbstractControl): ValidationErrors | null => {
    const start = group.get('startDate')?.value as string | null;
    const end = group.get('endDate')?.value as string | null;
    if (!start || !end) return null;
    return new Date(end) >= new Date(start) ? null : { endDateBeforeStart: true };
  };
}

/** Cross-field: timeTo must be > timeFrom */
function timeToValidator(): ValidatorFn {
  return (group: AbstractControl): ValidationErrors | null => {
    const from = group.get('timeFrom')?.value as string | null;
    const to = group.get('timeTo')?.value as string | null;
    if (!from || !to) return null;
    if (!TIME_PATTERN.test(from) || !TIME_PATTERN.test(to)) return null;
    return to > from ? null : { timeToNotAfterFrom: true };
  };
}

const ALL_DAYS: { value: ActiveDay; label: string }[] = [
  { value: 'MON', label: 'Pon' },
  { value: 'TUE', label: 'Wt' },
  { value: 'WED', label: 'Sr' },
  { value: 'THU', label: 'Czw' },
  { value: 'FRI', label: 'Pt' },
  { value: 'SAT', label: 'Sob' },
  { value: 'SUN', label: 'Nie' },
];

@Component({
  selector: 'app-campaign-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoModule,ReactiveFormsModule],
  templateUrl: './campaign-form.component.html',
  styleUrl: './campaign-form.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class CampaignFormComponent implements OnInit, AfterViewInit {
  readonly campaign = input<Campaign | null>(null);
  readonly isEditMode = input<boolean>(false);

  readonly saved = output<Campaign>();
  readonly cancelled = output<void>();

  private readonly campaignService = inject(CampaignService);
  private readonly queueService = inject(QueueService);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly submitting = signal(false);
  readonly queuesLoading = signal(false);
  readonly queues = signal<Queue[]>([]);
  readonly allDays = ALL_DAYS;

  readonly typeOptions: { value: CampaignType; label: string }[] = [
    { value: 'OUTBOUND_VOICE', label: 'Wychodzace glosy' },
    { value: 'OUTBOUND_EMAIL', label: 'Wychodzace email' },
  ];

  readonly dialerOptions: { value: DialerType; label: string }[] = [
    { value: 'PROGRESSIVE', label: 'Progresywny' },
    { value: 'PREDICTIVE', label: 'Predyktywny' },
    { value: 'MANUAL', label: 'Manualny' },
  ];

  readonly timezoneOptions: string[] = [
    'Europe/Warsaw',
    'UTC',
    'Europe/London',
    'Europe/Berlin',
    'America/New_York',
  ];

  readonly scheduleGroup = this.fb.group(
    {
      startDate: [''],
      endDate: [''],
      timeFrom: ['', [timePatternValidator]],
      timeTo: ['', [timePatternValidator]],
      timezone: ['Europe/Warsaw'],
    },
    { validators: [endDateValidator(), timeToValidator()] },
  );

  readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(255)]],
    type: ['OUTBOUND_VOICE' as CampaignType, Validators.required],
    dialerType: ['PROGRESSIVE' as DialerType, Validators.required],
    queueId: ['', Validators.required],
    maxAttempts: [3, [Validators.required, Validators.min(1)]],
    retryDelayMinutes: [60, [Validators.required, Validators.min(0)]],
    schedule: this.scheduleGroup,
  });

  /** Separate signal for active_days checkboxes (not in reactive form to keep it simple) */
  readonly selectedDays = signal<Set<ActiveDay>>(new Set());

  ngOnInit(): void {
    this.loadQueues();

    const editCampaign = this.campaign();
    if (this.isEditMode() && editCampaign) {
      this.form.patchValue({
        name: editCampaign.name,
        type: editCampaign.type,
        dialerType: editCampaign.dialerType,
        queueId: editCampaign.queueId ?? '',
        maxAttempts: editCampaign.maxAttempts,
        retryDelayMinutes: editCampaign.retryDelayMinutes,
      });
      if (editCampaign.schedule) {
        const s = editCampaign.schedule;
        this.scheduleGroup.patchValue({
          startDate: s.start_date ?? '',
          endDate: s.end_date ?? '',
          timeFrom: s.active_hours?.from ?? '',
          timeTo: s.active_hours?.to ?? '',
          timezone: s.timezone ?? 'Europe/Warsaw',
        });
        if (s.active_days) {
          this.selectedDays.set(new Set(s.active_days));
        }
      }
      // Only DRAFT can be edited
      if (editCampaign.status !== 'DRAFT') {
        this.form.disable();
      }
    }
  }

  private loadQueues(): void {
    this.queuesLoading.set(true);
    this.queueService
      .getQueues(0, 100)
      .pipe(
        catchError(() => {
          this.notifications.error('Nie udalo sie pobrac listy kolejek.');
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((result) => {
        this.queuesLoading.set(false);
        if (result) {
          this.queues.set(result.content.filter((q) => q.active));
        }
      });
  }

  ngAfterViewInit(): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
  }

  onEscapeKey(event: Event): void {
    event.preventDefault();
    this.onCancel();
  }

  toggleDay(day: ActiveDay): void {
    this.selectedDays.update((days) => {
      const next = new Set(days);
      if (next.has(day)) {
        next.delete(day);
      } else {
        next.add(day);
      }
      return next;
    });
  }

  isDaySelected(day: ActiveDay): boolean {
    return this.selectedDays().has(day);
  }

  private buildSchedule(): CampaignSchedule | undefined {
    const raw = this.scheduleGroup.getRawValue();
    const hasAnySchedule =
      raw.startDate || raw.endDate || raw.timeFrom || raw.timeTo || this.selectedDays().size > 0;

    if (!hasAnySchedule) return undefined;

    const schedule: CampaignSchedule = {
      timezone: raw.timezone || 'Europe/Warsaw',
    };
    if (raw.startDate) schedule.start_date = raw.startDate;
    if (raw.endDate) schedule.end_date = raw.endDate;
    if (raw.timeFrom && raw.timeTo) {
      schedule.active_hours = { from: raw.timeFrom, to: raw.timeTo };
    }
    if (this.selectedDays().size > 0) {
      schedule.active_days = Array.from(this.selectedDays()) as ActiveDay[];
    }
    return schedule;
  }

  // ── Error getters ────────────────────────────────────────────────────────────

  get nameError(): string | null {
    const ctrl = this.form.get('name')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Nazwa jest wymagana.';
    if (ctrl.hasError('maxlength')) return 'Nazwa nie moze przekraczac 255 znakow.';
    return null;
  }

  get maxAttemptsError(): string | null {
    const ctrl = this.form.get('maxAttempts')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Pole jest wymagane.';
    if (ctrl.hasError('min')) return 'Minimalna liczba prob to 1.';
    return null;
  }

  get retryDelayError(): string | null {
    const ctrl = this.form.get('retryDelayMinutes')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Pole jest wymagane.';
    if (ctrl.hasError('min')) return 'Opoznienie nie moze byc ujemne.';
    return null;
  }

  get timeFromError(): string | null {
    const ctrl = this.scheduleGroup.get('timeFrom')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('timePattern')) return 'Format: GG:MM (np. 09:00)';
    return null;
  }

  get timeToError(): string | null {
    const ctrl = this.scheduleGroup.get('timeTo')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('timePattern')) return 'Format: GG:MM (np. 18:00)';
    return null;
  }

  get scheduleTimeRangeError(): string | null {
    if (this.scheduleGroup.hasError('timeToNotAfterFrom') && this.scheduleGroup.touched) {
      return 'Godzina "do" musi byc pozniej niz godzina "od".';
    }
    return null;
  }

  get queueIdError(): string | null {
    const ctrl = this.form.get('queueId')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Kolejka jest wymagana.';
    return null;
  }

  get scheduleDateRangeError(): string | null {
    if (this.scheduleGroup.hasError('endDateBeforeStart') && this.scheduleGroup.touched) {
      return 'Data konca nie moze byc wczesniej niz data startu.';
    }
    return null;
  }

  get isSaveDisabled(): boolean {
    return this.form.invalid || this.submitting();
  }

  onSubmit(): void {
    this.form.markAllAsTouched();
    this.scheduleGroup.markAllAsTouched();
    if (this.form.invalid || this.submitting()) return;

    this.submitting.set(true);
    const raw = this.form.getRawValue();
    const schedule = this.buildSchedule();

    const editCampaign = this.campaign();
    if (this.isEditMode() && editCampaign) {
      this.campaignService
        .updateCampaign(editCampaign.campaignId, {
          name: raw.name!.trim(),
          dialerType: raw.dialerType as DialerType,
          maxAttempts: raw.maxAttempts!,
          retryDelayMinutes: raw.retryDelayMinutes!,
          schedule,
        })
        .pipe(
          catchError(() => {
            this.notifications.error('Nie udalo sie zaktualizowac kampanii. Sprobuj ponownie.');
            return of(null);
          }),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe((result) => {
          this.submitting.set(false);
          if (result) {
            this.notifications.success(`Kampania "${result.name}" zostala zaktualizowana.`);
            this.saved.emit(result);
          }
        });
    } else {
      this.campaignService
        .createCampaign({
          name: raw.name!.trim(),
          type: raw.type as CampaignType,
          dialerType: raw.dialerType as DialerType,
          queueId: raw.queueId!,
          maxAttempts: raw.maxAttempts!,
          retryDelayMinutes: raw.retryDelayMinutes!,
          schedule,
        })
        .pipe(
          catchError((err: { status?: number }) => {
            if (err?.status === 422) {
              this.notifications.error('Przekroczono limit kampanii dla tego tenanta.');
            } else {
              this.notifications.error('Nie udalo sie utworzyc kampanii. Sprobuj ponownie.');
            }
            return of(null);
          }),
          takeUntilDestroyed(this.destroyRef),
        )
        .subscribe((result) => {
          this.submitting.set(false);
          if (result) {
            this.notifications.success(`Kampania "${result.name}" zostala utworzona.`);
            this.saved.emit(result);
          }
        });
    }
  }

  onCancel(): void {
    this.cancelled.emit();
  }
}
