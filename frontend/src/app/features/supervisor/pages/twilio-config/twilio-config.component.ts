import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ConfirmDialogComponent } from '../../../../shared/components/confirm-dialog/confirm-dialog.component';
import { TwilioPhoneNumberSelectComponent } from '../../components/twilio-phone-number-select/twilio-phone-number-select.component';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { catchError, map, of, startWith } from 'rxjs';
import {
  TenantTwilioConfigRequest,
  TenantTwilioConfigResponse,
  TwilioConfigService,
  TwilioConnectionTestResult,
} from '../../services/twilio-config.service';
import { NotificationService } from '../../../../core/services/notification.service';

@Component({
  selector: 'app-twilio-config',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoModule,
    ReactiveFormsModule,
    ConfirmDialogComponent,
    TwilioPhoneNumberSelectComponent,
  ],
  templateUrl: './twilio-config.component.html',
  styleUrl: './twilio-config.component.scss',
})
export class TwilioConfigComponent implements OnInit {
  private readonly twilioConfigService = inject(TwilioConfigService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(true);
  readonly loadError = signal(false);
  readonly submitting = signal(false);
  readonly testing = signal(false);
  readonly hasConfig = signal(false);
  readonly showForm = signal(false);
  readonly connectionTestResult = signal<TwilioConnectionTestResult | null>(null);
  readonly showAuthToken = signal(false);
  readonly showApiKeySecret = signal(false);
  readonly revealAuthToken = signal(false);
  readonly revealApiKeySecret = signal(false);

  /** Masked placeholder values received from API */
  readonly authTokenMasked = signal<string | null>(null);
  readonly apiKeySecretMasked = signal<string | null>(null);

  private readonly _formDirty = signal(false);
  readonly showDeleteConfirm = signal(false);

  private testResultTimer: ReturnType<typeof setTimeout> | null = null;

  readonly form = this.fb.group({
    accountSid: ['', [Validators.required, Validators.pattern(/^AC[0-9a-fA-F]{32}$/)]],
    authToken: [''],
    apiKeySid: [''],
    apiKeySecret: [''],
    twimlAppSid: [''],
    phoneNumber: [null as string | null],
    statusCallbackUrl: [''],
  });

  private readonly _formValid = toSignal(
    this.form.statusChanges.pipe(
      startWith(this.form.status),
      map((s) => s === 'VALID'),
    ),
  );

  readonly isSaveDisabled = computed(() => {
    if (this.submitting() || !this._formDirty()) return true;
    // When editing, authToken is optional — clear its required validator from validity check
    if (this.hasConfig()) {
      const authCtrl = this.form.get('authToken')!;
      const authHasOnlyRequired =
        authCtrl.invalid && authCtrl.hasError('required') && !authCtrl.value?.trim();
      if (authHasOnlyRequired) {
        // Only authToken required error — ignore it, rest of form may be valid
        const otherControlsInvalid = Object.entries(
          this.form.controls as Record<string, { invalid: boolean; value: unknown }>,
        )
          .filter(([key]) => key !== 'authToken')
          .some(([, ctrl]) => ctrl.invalid);
        return otherControlsInvalid;
      }
    }
    return !this._formValid();
  });

  ngOnInit(): void {
    this.loadConfig();

    this.form.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this._formDirty.set(this.form.dirty);
      if (this.connectionTestResult() !== null) {
        this.connectionTestResult.set(null);
        if (this.testResultTimer !== null) {
          clearTimeout(this.testResultTimer);
          this.testResultTimer = null;
        }
      }
    });
  }

  private loadConfig(): void {
    this.loading.set(true);
    this.loadError.set(false);

    this.twilioConfigService
      .getConfig()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError(() => {
          this.loadError.set(true);
          this.loading.set(false);
          return of(null);
        }),
      )
      .subscribe((config) => {
        if (config) {
          this.hasConfig.set(true);
          this.showForm.set(true);
          this.patchFormFromResponse(config);
        } else {
          this.hasConfig.set(false);
          this.showForm.set(false);
        }
        this.loading.set(false);
      });
  }

  private patchFormFromResponse(config: TenantTwilioConfigResponse): void {
    this.authTokenMasked.set(config.authToken);
    this.apiKeySecretMasked.set(config.apiKeySecret);

    // clearValidators() is more reliable than removeValidators() — removes all validators
    // regardless of reference equality issues. authToken is never required when editing:
    // backend preserves the existing value when an empty string is sent.
    this.form.get('authToken')!.clearValidators();
    this.form.get('authToken')!.updateValueAndValidity({ emitEvent: false });

    this.form.patchValue({
      accountSid: config.accountSid ?? '',
      authToken: '',
      apiKeySid: config.apiKeySid ?? '',
      apiKeySecret: '',
      twimlAppSid: config.twimlAppSid ?? '',
      phoneNumber: config.phoneNumber ?? '',
      statusCallbackUrl: config.statusCallbackUrl ?? '',
    });
    this.form.markAsPristine();
    this.form.markAsUntouched();
    this._formDirty.set(false);
  }

  onSetupClick(): void {
    this.showForm.set(true);
    this.form.reset();
    this._formDirty.set(false);
    this.authTokenMasked.set(null);
    this.apiKeySecretMasked.set(null);
    this.showAuthToken.set(false);
    this.showApiKeySecret.set(false);
    this.revealAuthToken.set(false);
    this.revealApiKeySecret.set(false);

    // authToken required when creating new config
    this.form.get('authToken')!.addValidators(Validators.required);
    this.form.get('authToken')!.updateValueAndValidity();
  }

  onCancelSetup(): void {
    if (this.hasConfig()) {
      this.loadConfig();
      this.showAuthToken.set(false);
      this.showApiKeySecret.set(false);
      this.revealAuthToken.set(false);
      this.revealApiKeySecret.set(false);
    } else {
      this.showForm.set(false);
      this.form.reset();
      this._formDirty.set(false);
    }
  }

  onUnlockAuthToken(): void {
    this.showAuthToken.set(true);
    // No required validator when editing — backend preserves existing value if empty is sent
    this.form.get('authToken')!.markAsDirty();
  }

  onUnlockApiKeySecret(): void {
    this.showApiKeySecret.set(true);
    this.form.get('apiKeySecret')!.markAsDirty();
  }

  onSubmit(): void {
    // When editing an existing config, authToken is optional — backend preserves existing value.
    // Ensure no stale required validator is blocking the save.
    if (this.hasConfig()) {
      this.form.get('authToken')!.clearValidators();
      this.form.get('authToken')!.updateValueAndValidity({ emitEvent: false });
    }

    this.form.markAllAsTouched();
    if (this.form.invalid || this.submitting()) return;

    this.submitting.set(true);

    const raw = this.form.getRawValue();
    const request: TenantTwilioConfigRequest = {
      accountSid: raw.accountSid!.trim(),
      authToken: raw.authToken?.trim() || '',
      apiKeySid: raw.apiKeySid?.trim() || null,
      apiKeySecret: raw.apiKeySecret?.trim() || null,
      twimlAppSid: raw.twimlAppSid?.trim() || null,
      phoneNumber: raw.phoneNumber?.trim() || null,
      statusCallbackUrl: raw.statusCallbackUrl?.trim() || null,
    };

    this.twilioConfigService
      .saveConfig(request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (saved) => {
          this.submitting.set(false);
          this.hasConfig.set(true);
          this.showAuthToken.set(false);
          this.showApiKeySecret.set(false);
          this.revealAuthToken.set(false);
          this.revealApiKeySecret.set(false);
          this.patchFormFromResponse(saved);
          this.notifications.success(
            this.transloco.translate('supervisor.settings.twilioConfig.saveSuccess'),
          );
        },
        error: () => {
          this.submitting.set(false);
          this.notifications.error(this.transloco.translate('common.error'));
        },
      });
  }

  onDelete(): void {
    this.showDeleteConfirm.set(true);
  }

  onDeleteConfirmed(): void {
    this.showDeleteConfirm.set(false);
    this.twilioConfigService
      .deleteConfig()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.hasConfig.set(false);
          this.showForm.set(false);
          this.form.reset();
          this._formDirty.set(false);
          this.authTokenMasked.set(null);
          this.apiKeySecretMasked.set(null);
          this.connectionTestResult.set(null);
          this.notifications.success(
            this.transloco.translate('supervisor.settings.twilioConfig.deleteSuccess'),
          );
        },
        error: () => {
          this.notifications.error(this.transloco.translate('common.error'));
        },
      });
  }

  onDeleteCancelled(): void {
    this.showDeleteConfirm.set(false);
  }

  onTestConnection(): void {
    if (this.testing()) return;

    this.testing.set(true);
    this.connectionTestResult.set(null);
    if (this.testResultTimer !== null) {
      clearTimeout(this.testResultTimer);
      this.testResultTimer = null;
    }

    this.twilioConfigService
      .testConnection()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.connectionTestResult.set(result);
          this.testing.set(false);
          this.scheduleTestResultClear();
        },
        error: () => {
          this.connectionTestResult.set({
            success: false,
            message: this.transloco.translate('supervisor.settings.twilioConfig.testFailed'),
            testedAt: new Date().toISOString(),
          });
          this.testing.set(false);
          this.scheduleTestResultClear();
        },
      });
  }

  private scheduleTestResultClear(): void {
    this.testResultTimer = setTimeout(() => {
      this.connectionTestResult.set(null);
      this.testResultTimer = null;
    }, 30000);
  }

  get accountSidError(): string | null {
    const ctrl = this.form.get('accountSid')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return (
        this.transloco.translate('supervisor.settings.twilioConfig.accountSidLabel') +
        ' jest wymagany.'
      );
    if (ctrl.hasError('pattern'))
      return this.transloco.translate('supervisor.settings.twilioConfig.accountSidError');
    return null;
  }

  get authTokenError(): string | null {
    // When editing an existing config, Auth Token is never required — backend preserves
    // the stored value when an empty string is sent.
    if (this.hasConfig()) return null;
    const ctrl = this.form.get('authToken')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return (
        this.transloco.translate('supervisor.settings.twilioConfig.authTokenLabel') +
        ' jest wymagany.'
      );
    return null;
  }
}
