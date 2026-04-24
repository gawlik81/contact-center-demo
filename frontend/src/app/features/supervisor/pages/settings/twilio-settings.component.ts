import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { catchError, filter, of, switchMap, take } from 'rxjs';
import { AuthService } from '../../../../core/services/auth.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { TwilioConfigService } from '../../services/twilio-config.service';

const E164_PATTERN = /^\+[1-9]\d{6,14}$/;

@Component({
  selector: 'app-twilio-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule],
  templateUrl: './twilio-settings.component.html',
  styleUrl: './twilio-settings.component.scss',
})
export class TwilioSettingsComponent implements OnInit {
  private readonly twilioConfigService = inject(TwilioConfigService);
  private readonly notifications = inject(NotificationService);
  private readonly authService = inject(AuthService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(true);
  readonly loadError = signal(false);
  readonly submitting = signal(false);

  readonly hasPerTenantConfig = signal(false);
  readonly perTenantCallbackUrlEnabled = signal(false);

  readonly form = this.fb.group({
    twilioPhoneNumber: ['', [Validators.pattern(E164_PATTERN)]],
    twilioStatusCallbackUrl: ['', [Validators.maxLength(500)]],
  });

  private readonly formStatus = toSignal(this.form.statusChanges, {
    initialValue: this.form.status,
  });

  readonly isSaveDisabled = computed(
    () => this.formStatus() === 'INVALID' || this.submitting() || this.loading(),
  );

  readonly isRemoveDisabled = computed(() => this.submitting() || this.loading());

  readonly autoCallbackUrlPreview = computed(() => {
    const tenantId = this.authService.currentTenantId();
    const origin =
      typeof window !== 'undefined' ? window.location.origin : 'https://your-domain.com';
    return `${origin}/api/telephony/webhook/twilio${tenantId ? `?tenantId=${tenantId}` : ''}`;
  });

  readonly showCallbackUrlHint = computed(() => {
    const val = this.form.get('twilioStatusCallbackUrl')?.value;
    return !val || val.trim() === '';
  });

  ngOnInit(): void {
    this.twilioConfigService
      .getTelephonyFeatures()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((f) => this.perTenantCallbackUrlEnabled.set(f.perTenantCallbackUrlEnabled));

    // After a page reload the in-memory JWT payload is null until the silent
    // refresh triggered by AuthGuard completes. Listening on the signal avoids
    // showing a load error caused purely by the race between ngOnInit and the
    // async refresh. We take the first emission where tenantId is non-null.
    toObservable(this.authService.currentTenantId)
      .pipe(
        filter((tenantId): tenantId is string => !!tenantId),
        take(1),
        takeUntilDestroyed(this.destroyRef),
        switchMap((tenantId) => {
          this.loading.set(true);
          this.loadError.set(false);
          return this.twilioConfigService.getTenantConfig(tenantId).pipe(
            catchError(() => {
              this.loadError.set(true);
              this.loading.set(false);
              return of(null);
            }),
          );
        }),
      )
      .subscribe((tenant) => {
        if (tenant) {
          const phoneNumber = tenant.config?.twilio_phone_number ?? '';
          const callbackUrl = tenant.config?.twilio_status_callback_url ?? '';
          this.hasPerTenantConfig.set(!!phoneNumber);
          this.form.patchValue({
            twilioPhoneNumber: phoneNumber,
            twilioStatusCallbackUrl: callbackUrl,
          });
        }
        this.loading.set(false);
      });
  }

  onSubmit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.submitting()) return;

    const tenantId = this.authService.currentTenantId();
    if (!tenantId) return;

    const raw = this.form.getRawValue();
    const phoneNumber = raw.twilioPhoneNumber?.trim() || null;
    const callbackUrl = this.perTenantCallbackUrlEnabled()
      ? raw.twilioStatusCallbackUrl?.trim() || null
      : null;

    this.submitting.set(true);

    this.twilioConfigService
      .updateTwilioConfig(tenantId, {
        twilioPhoneNumber: phoneNumber,
        twilioStatusCallbackUrl: callbackUrl,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) => {
          this.submitting.set(false);
          const phone = updated.config?.twilio_phone_number ?? '';
          this.hasPerTenantConfig.set(!!phone);
          this.form.markAsPristine();
          this.notifications.success('Konfiguracja Twilio zaktualizowana.');
        },
        error: (err) => {
          this.submitting.set(false);
          const message = err?.error?.message;
          if (err?.status === 400 && message) {
            this.notifications.error(message);
          } else {
            this.notifications.error(
              'Nie udalo sie zapisac konfiguracji Twilio. Sprobuj ponownie.',
            );
          }
        },
      });
  }

  onRemoveConfig(): void {
    const tenantId = this.authService.currentTenantId();
    if (!tenantId) return;

    this.submitting.set(true);

    this.twilioConfigService
      .updateTwilioConfig(tenantId, {
        twilioPhoneNumber: null,
        twilioStatusCallbackUrl: null,
      })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.submitting.set(false);
          this.hasPerTenantConfig.set(false);
          this.form.patchValue({ twilioPhoneNumber: '', twilioStatusCallbackUrl: '' });
          this.form.markAsPristine();
          this.notifications.success('Konfiguracja per-tenant usunieta.');
        },
        error: () => {
          this.submitting.set(false);
          this.notifications.error(
            'Nie udalo sie usunac konfiguracji per-tenant. Sprobuj ponownie.',
          );
        },
      });
  }

  get phoneNumberError(): string | null {
    const ctrl = this.form.get('twilioPhoneNumber')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('pattern'))
      return 'Numer telefonu musi byc w formacie E.164, np. +48123456789.';
    return null;
  }

  get callbackUrlError(): string | null {
    const ctrl = this.form.get('twilioStatusCallbackUrl')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('maxlength')) return 'URL nie moze byc dluzszy niz 500 znakow.';
    return null;
  }
}
