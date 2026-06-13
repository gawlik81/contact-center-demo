import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  ElementRef,
  inject,
  input,
  OnInit,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  AsyncValidatorFn,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  ValidatorFn,
  Validators,
} from '@angular/forms';
import { catchError, debounceTime, distinctUntilChanged, first, map, of, switchMap } from 'rxjs';
import { CampaignService } from '../../../services/campaign.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { TwilioPhoneNumberSelectComponent } from '../../../components/twilio-phone-number-select/twilio-phone-number-select.component';
import { CampaignDispositionsComponent } from '../campaign-dispositions/campaign-dispositions.component';
import {
  ActiveDay,
  Campaign,
  CampaignSchedule,
  CampaignType,
  DialerType,
} from '../../../models/campaign.model';

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

const ALL_DAYS: { value: ActiveDay; labelKey: string }[] = [
  { value: 'MON', labelKey: 'agent.calendar.days.MON' },
  { value: 'TUE', labelKey: 'agent.calendar.days.TUE' },
  { value: 'WED', labelKey: 'agent.calendar.days.WED' },
  { value: 'THU', labelKey: 'agent.calendar.days.THU' },
  { value: 'FRI', labelKey: 'agent.calendar.days.FRI' },
  { value: 'SAT', labelKey: 'agent.calendar.days.SAT' },
  { value: 'SUN', labelKey: 'agent.calendar.days.SUN' },
];

@Component({
  selector: 'app-campaign-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, ReactiveFormsModule, TwilioPhoneNumberSelectComponent, CampaignDispositionsComponent],
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
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly submitting = signal(false);
  readonly allDays = ALL_DAYS;

  readonly typeOptions = signal<{ value: CampaignType; label: string }[]>([
    { value: 'OUTBOUND_VOICE', label: '' },
    { value: 'OUTBOUND_EMAIL', label: '' },
  ]);

  readonly dialerOptions = signal<{ value: DialerType; label: string }[]>([
    { value: 'PROGRESSIVE', label: '' },
    { value: 'PREDICTIVE', label: '' },
    { value: 'MANUAL', label: '' },
  ]);

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
    name: ['', [Validators.required, Validators.maxLength(255)], [this.createNameAsyncValidator()]],
    type: ['OUTBOUND_VOICE' as CampaignType, Validators.required],
    dialerType: ['PROGRESSIVE' as DialerType, Validators.required],
    callerId: this.fb.control<string | null>(null),
    maxAttempts: [3, [Validators.required, Validators.min(1), Validators.max(10)]],
    retryDelayMinutes: [60, [Validators.required, Validators.min(1), Validators.max(1440)]],
    ringTimeoutSeconds: [30, [Validators.required, Validators.min(15), Validators.max(120)]],
    schedule: this.scheduleGroup,
  });

  readonly campaignType = signal<string>(this.form.get('type')!.value ?? 'OUTBOUND_VOICE');
  readonly isOutboundVoice = computed(() => this.campaignType() === 'OUTBOUND_VOICE');

  /** Returns campaignId when in edit mode, undefined otherwise */
  readonly campaignId = computed(() =>
    this.isEditMode() ? (this.campaign()?.campaignId ?? undefined) : undefined,
  );

  /** Separate signal for active_days checkboxes (not in reactive form to keep it simple) */
  readonly selectedDays = signal<Set<ActiveDay>>(new Set());

  private createNameAsyncValidator(): AsyncValidatorFn {
    return (control: AbstractControl) => {
      const name: string = (control.value ?? '').trim();
      if (!name || name.length > 255) return of(null);

      const currentName = this.campaign()?.name;
      const excludeId = this.isEditMode() ? (this.campaign()?.campaignId ?? undefined) : undefined;

      if (this.isEditMode() && currentName && name.toLowerCase() === currentName.toLowerCase()) {
        return of(null);
      }

      return of(name).pipe(
        debounceTime(400),
        distinctUntilChanged(),
        switchMap((n) => this.campaignService.checkNameAvailability(n, excludeId)),
        map((res) => (res.available ? null : { nameTaken: true })),
        first(),
      );
    };
  }

  ngOnInit(): void {
    this.initOptions();

    this.transloco.langChanges$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.initOptions();
    });

    this.form
      .get('type')!
      .valueChanges.pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((val) => this.campaignType.set(val ?? 'OUTBOUND_VOICE'));

    const editCampaign = this.campaign();
    if (this.isEditMode() && editCampaign) {
      this.form.patchValue({
        name: editCampaign.name,
        type: editCampaign.type,
        dialerType: editCampaign.dialerType,
        callerId: editCampaign.callerId ?? null,
        maxAttempts: editCampaign.maxAttempts,
        retryDelayMinutes: editCampaign.retryDelayMinutes,
        ringTimeoutSeconds: editCampaign.ringTimeoutSeconds ?? 30,
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

  private initOptions(): void {
    this.typeOptions.set([
      {
        value: 'OUTBOUND_VOICE',
        label: this.transloco.translate('supervisor.campaigns.typeOutboundVoice'),
      },
      {
        value: 'OUTBOUND_EMAIL',
        label: this.transloco.translate('supervisor.campaigns.typeOutboundEmail'),
      },
    ]);
    this.dialerOptions.set([
      {
        value: 'PROGRESSIVE',
        label: this.transloco.translate('supervisor.campaigns.dialerProgressive'),
      },
      {
        value: 'PREDICTIVE',
        label: this.transloco.translate('supervisor.campaigns.dialerPredictive'),
      },
      {
        value: 'MANUAL',
        label: this.transloco.translate('supervisor.campaigns.dialerManual'),
      },
    ]);
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

  get nameChecking(): boolean {
    return this.form.get('name')!.status === 'PENDING';
  }

  get nameError(): string | null {
    const ctrl = this.form.get('name')!;
    if (ctrl.status === 'PENDING') return null;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('supervisor.campaignForm.errors.nameRequired');
    if (ctrl.hasError('maxlength'))
      return this.transloco.translate('supervisor.campaignForm.errors.nameMaxLength');
    if (ctrl.hasError('nameTaken'))
      return this.transloco.translate('supervisor.campaignForm.errors.nameTaken');
    return null;
  }

  get maxAttemptsError(): string | null {
    const ctrl = this.form.get('maxAttempts')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('supervisor.campaignForm.errors.fieldRequired');
    if (ctrl.hasError('min'))
      return this.transloco.translate('supervisor.campaignForm.errors.maxAttemptsMin');
    if (ctrl.hasError('max'))
      return this.transloco.translate('supervisor.campaignForm.errors.maxAttemptsMax');
    return null;
  }

  get retryDelayError(): string | null {
    const ctrl = this.form.get('retryDelayMinutes')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('supervisor.campaignForm.errors.fieldRequired');
    if (ctrl.hasError('min'))
      return this.transloco.translate('supervisor.campaignForm.errors.retryDelayMin');
    if (ctrl.hasError('max'))
      return this.transloco.translate('supervisor.campaignForm.errors.retryDelayMax');
    return null;
  }

  get ringTimeoutError(): string | null {
    const ctrl = this.form.get('ringTimeoutSeconds')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('supervisor.campaignForm.errors.fieldRequired');
    if (ctrl.hasError('min'))
      return this.transloco.translate('supervisor.campaignForm.errors.ringTimeoutMin');
    if (ctrl.hasError('max'))
      return this.transloco.translate('supervisor.campaignForm.errors.ringTimeoutMax');
    return null;
  }

  get timeFromError(): string | null {
    const ctrl = this.scheduleGroup.get('timeFrom')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('timePattern'))
      return this.transloco.translate('supervisor.campaignForm.errors.timeFromPattern');
    return null;
  }

  get timeToError(): string | null {
    const ctrl = this.scheduleGroup.get('timeTo')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('timePattern'))
      return this.transloco.translate('supervisor.campaignForm.errors.timeToPattern');
    return null;
  }

  get scheduleTimeRangeError(): string | null {
    if (this.scheduleGroup.hasError('timeToNotAfterFrom') && this.scheduleGroup.touched) {
      return this.transloco.translate('supervisor.campaignForm.errors.timeRangeInvalid');
    }
    return null;
  }

  get scheduleDateRangeError(): string | null {
    if (this.scheduleGroup.hasError('endDateBeforeStart') && this.scheduleGroup.touched) {
      return this.transloco.translate('supervisor.campaignForm.errors.dateRangeInvalid');
    }
    return null;
  }

  get isSaveDisabled(): boolean {
    return this.form.invalid || this.submitting() || this.form.get('name')!.status === 'PENDING';
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
          ringTimeoutSeconds: raw.ringTimeoutSeconds ?? undefined,
          schedule,
          callerId: raw.callerId || null,
        })
        .pipe(
          catchError(() => {
            this.notifications.error(
              this.transloco.translate('supervisor.campaignForm.errors.updateFailed'),
            );
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
          maxAttempts: raw.maxAttempts!,
          retryDelayMinutes: raw.retryDelayMinutes!,
          ringTimeoutSeconds: raw.ringTimeoutSeconds!,
          schedule,
          callerId: raw.callerId || null,
        })
        .pipe(
          catchError((err: { status?: number }) => {
            if (err?.status === 422) {
              this.notifications.error(
                this.transloco.translate('supervisor.campaignForm.errors.limitExceeded'),
              );
            } else {
              this.notifications.error(
                this.transloco.translate('supervisor.campaignForm.errors.createFailed'),
              );
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
