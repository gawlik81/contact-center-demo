import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  output,
  signal,
  viewChild,
} from '@angular/core';
import {
  AbstractControl,
  AsyncValidatorFn,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, catchError, map, of, switchMap, timer, EMPTY } from 'rxjs';
import { TenantService } from '../tenant.service';
import { NotificationService } from '../../../core/services/notification.service';
import { TwilioConfigService } from '../../supervisor/services/twilio-config.service';
import { Tenant } from '../tenant.model';

function nameAvailabilityValidator(tenantService: TenantService): AsyncValidatorFn {
  return (control: AbstractControl): Observable<ValidationErrors | null> => {
    const value: string = control.value?.trim() ?? '';
    if (!value || value.length < 2) {
      return of(null);
    }
    return timer(500).pipe(
      switchMap(() => tenantService.checkNameAvailability(value)),
      map((response) => (response.available ? null : { nameTaken: true })),
      catchError(() => of(null)),
    );
  };
}

const E164_REGEX = /^\+[1-9]\d{6,14}$/;

@Component({
  selector: 'app-tenant-add-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule],
  templateUrl: './tenant-add-modal.component.html',
  styleUrl: './tenant-add-modal.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class TenantAddModalComponent implements AfterViewInit {
  readonly tenantAdded = output<Tenant>();

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');
  private readonly fb = inject(FormBuilder);
  private readonly tenantService = inject(TenantService);
  private readonly twilioConfigService = inject(TwilioConfigService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  readonly submitting = signal(false);
  readonly visible = signal(false);

  readonly form = this.fb.group({
    name: [
      '',
      {
        validators: [Validators.required, Validators.minLength(2), Validators.maxLength(100)],
        asyncValidators: [nameAvailabilityValidator(this.tenantService)],
        // 'blur' prevents multiple async validator calls on each keystroke
        updateOn: 'blur',
      },
    ],
    maxAgents: [
      10,
      [Validators.required, Validators.min(1), Validators.max(500), Validators.pattern(/^\d+$/)],
    ],
    maxQueues: [
      5,
      [Validators.required, Validators.min(1), Validators.max(100), Validators.pattern(/^\d+$/)],
    ],
    maxCampaigns: [
      3,
      [Validators.required, Validators.min(1), Validators.max(100), Validators.pattern(/^\d+$/)],
    ],
    twilioPhoneNumber: ['', [Validators.pattern(E164_REGEX)]],
  });

  ngAfterViewInit(): void {
    // dialog is controlled via open()/close() — not opened automatically
  }

  open(): void {
    this.form.reset({
      name: '',
      maxAgents: 10,
      maxQueues: 5,
      maxCampaigns: 3,
      twilioPhoneNumber: '',
    });
    this.visible.set(true);
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
  }

  close(): void {
    this.visible.set(false);
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && dialog.open) {
      dialog.close();
    }
  }

  onEscapeKey(event: Event): void {
    if (this.visible()) {
      event.preventDefault();
      this.close();
    }
  }

  onBackdropClick(event: MouseEvent): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (event.target === dialog) {
      this.close();
    }
  }

  // ── Control accessors ──────────────────────────────────────────────────────
  get nameCtrl() {
    return this.form.get('name')!;
  }
  get maxAgentsCtrl() {
    return this.form.get('maxAgents')!;
  }
  get maxQueuesCtrl() {
    return this.form.get('maxQueues')!;
  }
  get maxCampaignsCtrl() {
    return this.form.get('maxCampaigns')!;
  }
  get twilioPhoneNumberCtrl() {
    return this.form.get('twilioPhoneNumber')!;
  }

  // ── Validation helpers ─────────────────────────────────────────────────────
  get nameValidating(): boolean {
    return this.nameCtrl.pending;
  }

  get nameError(): string | null {
    const ctrl = this.nameCtrl;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Nazwa jest wymagana.';
    if (ctrl.hasError('minlength')) return 'Nazwa musi miec co najmniej 2 znaki.';
    if (ctrl.hasError('maxlength')) return 'Nazwa nie moze przekraczac 100 znakow.';
    if (ctrl.hasError('nameTaken')) return 'Ta nazwa jest juz zajeta.';
    return null;
  }

  get maxAgentsError(): string | null {
    const ctrl = this.maxAgentsCtrl;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Pole jest wymagane.';
    if (ctrl.hasError('min')) return 'Minimalna wartosc to 1.';
    if (ctrl.hasError('max')) return 'Maksymalna wartosc to 500.';
    if (ctrl.hasError('pattern')) return 'Podaj liczbe calkowita.';
    return null;
  }

  get maxQueuesError(): string | null {
    const ctrl = this.maxQueuesCtrl;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Pole jest wymagane.';
    if (ctrl.hasError('min')) return 'Minimalna wartosc to 1.';
    if (ctrl.hasError('max')) return 'Maksymalna wartosc to 100.';
    if (ctrl.hasError('pattern')) return 'Podaj liczbe calkowita.';
    return null;
  }

  get maxCampaignsError(): string | null {
    const ctrl = this.maxCampaignsCtrl;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Pole jest wymagane.';
    if (ctrl.hasError('min')) return 'Minimalna wartosc to 1.';
    if (ctrl.hasError('max')) return 'Maksymalna wartosc to 100.';
    if (ctrl.hasError('pattern')) return 'Podaj liczbe calkowita.';
    return null;
  }

  get twilioPhoneNumberError(): string | null {
    const ctrl = this.twilioPhoneNumberCtrl;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('pattern')) return 'Podaj numer w formacie E.164, np. +48123456789';
    return null;
  }

  get isSaveDisabled(): boolean {
    return this.form.invalid || this.form.pending || this.submitting();
  }

  // ── Submit ─────────────────────────────────────────────────────────────────
  onSubmit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.form.pending || this.submitting()) return;

    this.submitting.set(true);
    const raw = this.form.getRawValue();
    const phoneNumber = raw.twilioPhoneNumber?.trim() || null;

    this.tenantService
      .createTenant({
        name: raw.name!.trim(),
        limits: {
          max_agents: Number(raw.maxAgents),
          max_queues: Number(raw.maxQueues),
          max_campaigns: Number(raw.maxCampaigns),
        },
      })
      .pipe(
        switchMap((tenant) => {
          if (!phoneNumber) return of(tenant);
          return this.twilioConfigService
            .updateTwilioConfig(tenant.id, {
              twilioPhoneNumber: phoneNumber,
              twilioStatusCallbackUrl: null,
            })
            .pipe(
              map(() => tenant),
              catchError(() => {
                this.notifications.error(
                  'Tenant zostal utworzony, ale nie udalo sie zapisac numeru Twilio.',
                );
                return of(tenant);
              }),
            );
        }),
        catchError(() => {
          this.submitting.set(false);
          this.notifications.error('Nie udalo sie utworzyc tenanta. Sprobuj ponownie.');
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((tenant) => {
        this.submitting.set(false);
        this.notifications.success(`Tenant "${tenant.name}" zostal utworzony.`);
        this.tenantAdded.emit(tenant);
        this.close();
      });
  }

  onCancel(): void {
    this.close();
  }
}
