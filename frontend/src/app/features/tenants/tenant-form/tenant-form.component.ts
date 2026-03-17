import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  signal,
} from '@angular/core';
import {
  ReactiveFormsModule,
  FormBuilder,
  Validators,
  AbstractControl,
  AsyncValidatorFn,
  ValidationErrors,
} from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import { Observable, timer, switchMap, map, catchError, of } from 'rxjs';
import { TenantService } from '../tenant.service';
import { NotificationService } from '../../../core/services/notification.service';

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

@Component({
  selector: 'app-tenant-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule],
  templateUrl: './tenant-form.component.html',
  styleUrl: './tenant-form.component.scss',
})
export class TenantFormComponent {
  private readonly fb = inject(FormBuilder);
  private readonly tenantService = inject(TenantService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly submitting = signal(false);

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
  });

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

  // ── Validation helpers (getters – re-evaluated by CD on every check) ───────
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

  get isSaveDisabled(): boolean {
    return this.form.invalid || this.form.pending || this.submitting();
  }

  // ── Submit ─────────────────────────────────────────────────────────────────
  onSubmit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.form.pending || this.submitting()) return;

    this.submitting.set(true);
    const raw = this.form.getRawValue();

    this.tenantService
      .createTenant({
        name: raw.name!.trim(),
        limits: {
          max_agents: Number(raw.maxAgents),
          max_queues: Number(raw.maxQueues),
          max_campaigns: Number(raw.maxCampaigns),
        },
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (tenant) => {
          this.submitting.set(false);
          this.notifications.success(`Tenant "${tenant.name}" zostal utworzony.`);
          this.router.navigate(['/admin/tenants']);
        },
        error: () => {
          this.submitting.set(false);
          this.notifications.error('Nie udalo sie utworzyc tenanta. Sprobuj ponownie.');
        },
      });
  }

  onCancel(): void {
    this.router.navigate(['/admin/tenants']);
  }
}
