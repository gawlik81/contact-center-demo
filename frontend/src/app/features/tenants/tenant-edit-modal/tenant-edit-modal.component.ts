import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  inject,
  input,
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
  Validators,
} from '@angular/forms';
import { Observable, catchError, map, of, switchMap, timer } from 'rxjs';
import { TenantService } from '../tenant.service';
import { NotificationService } from '../../../core/services/notification.service';
import { Tenant, TenantStatus } from '../tenant.model';
import { TwilioConfigService } from '../../supervisor/services/twilio-config.service';

function nameAvailabilityForUpdateValidator(
  tenantService: TenantService,
  tenantId: string,
): AsyncValidatorFn {
  return (control: AbstractControl): Observable<ValidationErrors | null> => {
    const value: string = control.value?.trim() ?? '';
    if (!value || value.length < 2) {
      return of(null);
    }
    return timer(500).pipe(
      switchMap(() => tenantService.checkNameAvailabilityForUpdate(tenantId, value)),
      map((response) => (response.available ? null : { nameTaken: true })),
      catchError(() => of(null)),
    );
  };
}

@Component({
  selector: 'app-tenant-edit-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule],
  templateUrl: './tenant-edit-modal.component.html',
  styleUrl: './tenant-edit-modal.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class TenantEditModalComponent implements OnInit, AfterViewInit {
  readonly tenant = input.required<Tenant>();

  readonly saved = output<void>();
  readonly cancelled = output<void>();

  private readonly tenantService = inject(TenantService);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);
  private readonly twilioConfigService = inject(TwilioConfigService);

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly submitting = signal(false);
  readonly twilioSubmitting = signal(false);
  readonly twilioLoadError = signal(false);

  readonly statusOptions: { value: TenantStatus; label: string }[] = [
    { value: 'ACTIVE', label: 'Aktywny' },
    { value: 'INACTIVE', label: 'Nieaktywny' },
    { value: 'SUSPENDED', label: 'Zawieszony' },
  ];

  readonly twilioForm = this.fb.group({
    twilioPhoneNumber: ['', [Validators.pattern(/^\+[1-9]\d{6,14}$/)]],
    twilioStatusCallbackUrl: ['', [Validators.maxLength(500)]],
  });

  readonly form = this.fb.group({
    name: ['', {
      validators: [Validators.required, Validators.minLength(2), Validators.maxLength(255)],
      updateOn: 'blur',
    }],
    status: ['ACTIVE' as TenantStatus, Validators.required],
    maxAgents: [10, [Validators.required, Validators.min(1), Validators.max(500), Validators.pattern(/^\d+$/)]],
    maxQueues: [5, [Validators.required, Validators.min(1), Validators.max(100), Validators.pattern(/^\d+$/)]],
    maxCampaigns: [3, [Validators.required, Validators.min(1), Validators.max(100), Validators.pattern(/^\d+$/)]],
  });

  ngOnInit(): void {
    const t = this.tenant();

    // Ustawiamy async validator dopiero po poznaniu ID tenanta
    this.form.get('name')?.addAsyncValidators(
      nameAvailabilityForUpdateValidator(this.tenantService, t.id),
    );
    this.form.get('name')?.updateValueAndValidity();

    this.form.patchValue({
      name: t.name,
      status: t.status,
      maxAgents: t.config.max_agents ?? 10,
      maxQueues: t.config.max_queues ?? 5,
      maxCampaigns: t.config.max_campaigns ?? 3,
    });

    this.twilioForm.patchValue({
      twilioPhoneNumber: t.config.twilio_phone_number ?? '',
      twilioStatusCallbackUrl: t.config.twilio_status_callback_url ?? '',
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

  get nameCtrl() {
    return this.form.get('name')!;
  }

  get nameValidating(): boolean {
    return this.nameCtrl.pending;
  }

  get nameError(): string | null {
    const ctrl = this.nameCtrl;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Nazwa jest wymagana.';
    if (ctrl.hasError('minlength')) return 'Nazwa musi miec co najmniej 2 znaki.';
    if (ctrl.hasError('maxlength')) return 'Nazwa nie moze przekraczac 255 znakow.';
    if (ctrl.hasError('nameTaken')) return 'Ta nazwa jest juz zajeta.';
    return null;
  }

  get maxAgentsError(): string | null {
    const ctrl = this.form.get('maxAgents')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Pole jest wymagane.';
    if (ctrl.hasError('min')) return 'Minimalna wartosc to 1.';
    if (ctrl.hasError('max')) return 'Maksymalna wartosc to 500.';
    if (ctrl.hasError('pattern')) return 'Podaj liczbe calkowita.';
    return null;
  }

  get maxQueuesError(): string | null {
    const ctrl = this.form.get('maxQueues')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Pole jest wymagane.';
    if (ctrl.hasError('min')) return 'Minimalna wartosc to 1.';
    if (ctrl.hasError('max')) return 'Maksymalna wartosc to 100.';
    if (ctrl.hasError('pattern')) return 'Podaj liczbe calkowita.';
    return null;
  }

  get maxCampaignsError(): string | null {
    const ctrl = this.form.get('maxCampaigns')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Pole jest wymagane.';
    if (ctrl.hasError('min')) return 'Minimalna wartosc to 1.';
    if (ctrl.hasError('max')) return 'Maksymalna wartosc to 100.';
    if (ctrl.hasError('pattern')) return 'Podaj liczbe calkowita.';
    return null;
  }

  get isSaveDisabled(): boolean {
    return this.form.invalid || this.form.pending || this.submitting();
  }

  onSubmit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.form.pending || this.submitting()) return;

    this.submitting.set(true);
    const raw = this.form.getRawValue();
    const tenantId = this.tenant().id;

    this.tenantService
      .updateTenant(tenantId, {
        name: raw.name!.trim(),
        status: raw.status as TenantStatus,
        limits: {
          max_agents: Number(raw.maxAgents),
          max_queues: Number(raw.maxQueues),
          max_campaigns: Number(raw.maxCampaigns),
        },
      })
      .pipe(
        catchError(() => {
          this.submitting.set(false);
          this.notifications.error('Nie udalo sie zaktualizowac tenanta. Sprobuj ponownie.');
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((updated) => {
        if (updated) {
          this.submitting.set(false);
          this.notifications.success(`Tenant "${updated.name}" zostal zaktualizowany.`);
          this.saved.emit();
        }
      });
  }

  get twilioPhoneNumberError(): string | null {
    const ctrl = this.twilioForm.get('twilioPhoneNumber')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('pattern')) return 'Podaj numer w formacie E.164, np. +48123456789.';
    return null;
  }

  onSaveTwilio(): void {
    this.twilioForm.markAllAsTouched();
    if (this.twilioForm.invalid || this.twilioSubmitting()) return;
    const tenantId = this.tenant().id;
    const raw = this.twilioForm.getRawValue();
    this.twilioSubmitting.set(true);
    this.twilioConfigService.updateTwilioConfig(tenantId, {
      twilioPhoneNumber: raw.twilioPhoneNumber?.trim() || null,
      twilioStatusCallbackUrl: raw.twilioStatusCallbackUrl?.trim() || null,
    }).pipe(
      catchError((err) => {
        this.twilioSubmitting.set(false);
        const msg = err?.error?.message;
        this.notifications.error(msg && err?.status === 400 ? msg : 'Nie udalo sie zapisac konfiguracji Twilio.');
        return of(null);
      }),
      takeUntilDestroyed(this.destroyRef),
    ).subscribe(updated => {
      if (updated) {
        this.twilioSubmitting.set(false);
        this.twilioForm.markAsPristine();
        this.notifications.success('Konfiguracja Twilio zaktualizowana.');
      }
    });
  }

  onCancel(): void {
    this.cancelled.emit();
  }
}
