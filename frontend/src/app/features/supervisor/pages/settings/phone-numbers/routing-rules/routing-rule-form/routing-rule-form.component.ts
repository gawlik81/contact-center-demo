import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  ViewChild,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { catchError, of, startWith } from 'rxjs';
import { IvrService } from '../../../../../services/ivr.service';
import { QueueService } from '../../../../../services/queue.service';
import { PhoneNumberService } from '../../../../../services/phone-number.service';
import { NotificationService } from '../../../../../../../core/services/notification.service';
import { ALL_DAYS, DAY_LABELS, PhoneRoutingRule } from '../../../../../models/phone-number.model';
import { IvrResponse } from '../../../../../models/ivr.model';
import { Queue } from '../../../../../models/queue.model';

function timeRangeValidator(group: AbstractControl): ValidationErrors | null {
  const start = group.get('timeStart')?.value as string | null;
  const end = group.get('timeEnd')?.value as string | null;
  if (!start || !end) return null;
  return end > start ? null : { timeRange: true };
}

function atLeastOneDayValidator(control: AbstractControl): ValidationErrors | null {
  const val = control.value as number[];
  return val && val.length > 0 ? null : { noDays: true };
}

@Component({
  selector: 'app-routing-rule-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, ReactiveFormsModule],
  templateUrl: './routing-rule-form.component.html',
  styleUrl: './routing-rule-form.component.scss',
})
export class RoutingRuleFormComponent implements OnInit {
  @ViewChild('dialogRef') private dialogRef!: ElementRef<HTMLDialogElement>;

  readonly phoneNumberId = input.required<string>();
  readonly editRule = input<PhoneRoutingRule | null>(null);
  readonly saved = output<PhoneRoutingRule>();
  readonly cancelled = output<void>();

  private readonly fb = inject(FormBuilder);
  private readonly ivrService = inject(IvrService);
  private readonly queueService = inject(QueueService);
  private readonly phoneNumberService = inject(PhoneNumberService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  constructor() {
    effect(() => {
      const rule = this.editRule();
      if (rule) {
        this.form.patchValue({
          daysOfWeek: [...rule.daysOfWeek],
          timeStart: rule.timeStart,
          timeEnd: rule.timeEnd,
          targetType: rule.ivrTreeId ? 'ivr' : 'queue',
          ivrTreeId: rule.ivrTreeId ?? '',
          queueId: rule.queueId ?? '',
        });
      } else {
        this.form.reset({
          daysOfWeek: [],
          timeStart: '08:00',
          timeEnd: '17:00',
          targetType: 'ivr',
          ivrTreeId: '',
          queueId: '',
        });
      }
    });
  }

  readonly allDays = ALL_DAYS;
  readonly dayLabels = DAY_LABELS;

  readonly loadingOptions = signal(true);
  readonly ivrTrees = signal<IvrResponse[]>([]);
  readonly queues = signal<Queue[]>([]);
  readonly submitting = signal(false);

  readonly form = this.fb.group(
    {
      daysOfWeek: [[] as number[], [atLeastOneDayValidator]],
      timeStart: ['08:00', Validators.required],
      timeEnd: ['17:00', Validators.required],
      targetType: ['ivr' as 'ivr' | 'queue', Validators.required],
      ivrTreeId: [''],
      queueId: [''],
    },
    { validators: timeRangeValidator },
  );

  readonly targetType = toSignal(
    this.form.get('targetType')!.valueChanges.pipe(startWith(this.form.get('targetType')!.value)),
    { initialValue: 'ivr' as 'ivr' | 'queue' },
  );

  readonly isEditMode = computed(() => !!this.editRule());

  readonly isSaveDisabled = computed(() => this.submitting() || this.loadingOptions());

  ngOnInit(): void {
    this.loadOptions();
  }

  open(): void {
    this.dialogRef.nativeElement.showModal();
  }

  close(): void {
    this.dialogRef.nativeElement.close();
    this.cancelled.emit();
  }

  isDaySelected(day: number): boolean {
    const days = this.form.get('daysOfWeek')?.value as number[];
    return days?.includes(day) ?? false;
  }

  toggleDay(day: number): void {
    const ctrl = this.form.get('daysOfWeek')!;
    const current = (ctrl.value as number[]) ?? [];
    const next = current.includes(day) ? current.filter((d) => d !== day) : [...current, day];
    ctrl.setValue(next);
    ctrl.markAsTouched();
  }

  onSubmit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.submitting()) return;

    const raw = this.form.getRawValue();
    const targetType = raw.targetType;
    const req = {
      daysOfWeek: raw.daysOfWeek as number[],
      timeStart: raw.timeStart!,
      timeEnd: raw.timeEnd!,
      ivrTreeId: targetType === 'ivr' ? raw.ivrTreeId || null : null,
      queueId: targetType === 'queue' ? raw.queueId || null : null,
      isActive: true,
    };

    this.submitting.set(true);
    const phoneNumberId = this.phoneNumberId();
    const rule = this.editRule();

    const request$ = rule
      ? this.phoneNumberService.updateRoutingRule(phoneNumberId, rule.ruleId, req)
      : this.phoneNumberService.createRoutingRule(phoneNumberId, req);

    request$
      .pipe(
        catchError((err: unknown) => {
          this.submitting.set(false);
          const httpErr = err as { status?: number; error?: { message?: string } };
          if (httpErr?.status === 409) {
            const msg = httpErr?.error?.message ?? 'Reguła koliduje z istniejącą regułą.';
            this.notifications.error(msg);
          } else {
            this.notifications.error(
              this.transloco.translate('supervisor.settings.routingRules.errorSave'),
            );
          }
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((result) => {
        if (!result) return;
        this.submitting.set(false);
        this.notifications.success(
          this.transloco.translate('supervisor.settings.routingRules.successSave'),
        );
        this.saved.emit(result);
        this.dialogRef.nativeElement.close();
      });
  }

  private loadOptions(): void {
    this.loadingOptions.set(true);
    let ivrDone = false;
    let queueDone = false;

    const checkDone = () => {
      if (ivrDone && queueDone) this.loadingOptions.set(false);
    };

    this.ivrService
      .getIvrList()
      .pipe(
        catchError(() => of([])),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((trees) => {
        this.ivrTrees.set(trees.filter((t) => t.is_active));
        ivrDone = true;
        checkDone();
      });

    this.queueService
      .getQueues(0, 100)
      .pipe(
        catchError(() => of({ content: [] })),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((page) => {
        this.queues.set(page.content ?? []);
        queueDone = true;
        checkDone();
      });
  }

  get daysError(): boolean {
    const ctrl = this.form.get('daysOfWeek')!;
    return ctrl.touched && ctrl.hasError('noDays');
  }

  get timeRangeError(): boolean {
    return this.form.touched && !!this.form.hasError('timeRange');
  }
}
