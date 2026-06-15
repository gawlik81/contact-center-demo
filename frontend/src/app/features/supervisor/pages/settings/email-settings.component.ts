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
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { catchError, of } from 'rxjs';
import {
  EmailConfigRequest,
  EmailConfigService,
  TestConnectionResponse,
} from '../../services/email-config.service';
import { NotificationService } from '../../../../core/services/notification.service';

@Component({
  selector: 'app-email-settings',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, ReactiveFormsModule],
  templateUrl: './email-settings.component.html',
  styleUrl: './email-settings.component.scss',
})
export class EmailSettingsComponent implements OnInit {
  private readonly emailConfigService = inject(EmailConfigService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);
  private readonly http = inject(HttpClient);

  readonly loading = signal(true);
  readonly queues = signal<{ queueId: string; name: string }[]>([]);
  readonly loadError = signal(false);
  readonly submitting = signal(false);
  readonly testing = signal(false);
  readonly hasPassword = signal(false);
  readonly testResult = signal<TestConnectionResponse | null>(null);

  readonly form = this.fb.group({
    emailEnabled: [false],
    imapHost: ['', [Validators.required]],
    imapPort: [993, [Validators.required, Validators.min(1), Validators.max(65535)]],
    imapSsl: [true],
    smtpHost: ['', [Validators.required]],
    smtpPort: [587, [Validators.required, Validators.min(1), Validators.max(65535)]],
    smtpSsl: [true],
    username: ['', [Validators.required, Validators.email]],
    password: [''],
    pollIntervalSeconds: [60, [Validators.required, Validators.min(10), Validators.max(3600)]],
    defaultQueueId: [null as string | null],
  });

  private readonly formStatus = toSignal(this.form.statusChanges, {
    initialValue: this.form.status,
  });

  readonly isSaveDisabled = computed(
    () => this.formStatus() === 'INVALID' || this.submitting() || this.loading(),
  );
  readonly isTestDisabled = computed(
    () => this.formStatus() === 'INVALID' || this.testing() || this.submitting() || this.loading(),
  );

  ngOnInit(): void {
    this.loadConfig();
    this.http
      .get<{ content: { queueId: string; name: string }[] }>('/api/queues')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: (res) => this.queues.set(res.content) });
  }

  private loadConfig(): void {
    this.loading.set(true);
    this.loadError.set(false);

    this.emailConfigService
      .getConfig()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError((err) => {
          if (err?.status === 404) {
            return of(null);
          }
          this.loadError.set(true);
          this.loading.set(false);
          return of(null);
        }),
      )
      .subscribe((config) => {
        if (config) {
          this.hasPassword.set(config.hasPassword);
          this.form.patchValue({
            emailEnabled: config.emailEnabled,
            imapHost: config.imapHost,
            imapPort: config.imapPort,
            imapSsl: config.imapSsl,
            smtpHost: config.smtpHost,
            smtpPort: config.smtpPort,
            smtpSsl: config.smtpSsl,
            username: config.username,
            pollIntervalSeconds: config.pollIntervalSeconds,
            defaultQueueId: config.defaultQueueId ?? null,
          });
        }
        this.loading.set(false);
      });
  }

  private buildRequest(): EmailConfigRequest {
    const raw = this.form.getRawValue();
    const passwordValue = raw.password?.trim() || null;
    return {
      emailEnabled: raw.emailEnabled ?? false,
      imapHost: raw.imapHost!.trim(),
      imapPort: raw.imapPort!,
      imapSsl: raw.imapSsl ?? true,
      smtpHost: raw.smtpHost!.trim(),
      smtpPort: raw.smtpPort!,
      smtpSsl: raw.smtpSsl ?? true,
      username: raw.username!.trim(),
      password: passwordValue,
      pollIntervalSeconds: raw.pollIntervalSeconds!,
      defaultQueueId:
        raw.defaultQueueId && raw.defaultQueueId !== 'undefined' ? raw.defaultQueueId : null,
    };
  }

  onTestConnection(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.testing()) return;

    this.testing.set(true);
    this.testResult.set(null);

    this.emailConfigService
      .testConnection(this.buildRequest())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.testResult.set(result);
          this.testing.set(false);
        },
        error: () => {
          this.testResult.set({
            success: false,
            message: 'Blad polaczenia. Sprawdz ustawienia serwera.',
          });
          this.testing.set(false);
        },
      });
  }

  onSubmit(): void {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.submitting()) return;

    this.submitting.set(true);

    this.emailConfigService
      .updateConfig(this.buildRequest())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (updated) => {
          this.submitting.set(false);
          this.hasPassword.set(updated.hasPassword);
          this.form.patchValue({ password: '' });
          this.form.markAsPristine();
          this.notifications.success('Konfiguracja email zapisana.');
        },
        error: () => {
          this.submitting.set(false);
          this.notifications.error('Nie udalo sie zapisac konfiguracji. Sprobuj ponownie.');
        },
      });
  }

  get imapHostError(): string | null {
    const ctrl = this.form.get('imapHost')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Host IMAP jest wymagany.';
    return null;
  }

  get imapPortError(): string | null {
    const ctrl = this.form.get('imapPort')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Port IMAP jest wymagany.';
    if (ctrl.hasError('min') || ctrl.hasError('max')) return 'Port musi byc w zakresie 1-65535.';
    return null;
  }

  get smtpHostError(): string | null {
    const ctrl = this.form.get('smtpHost')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Host SMTP jest wymagany.';
    return null;
  }

  get smtpPortError(): string | null {
    const ctrl = this.form.get('smtpPort')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Port SMTP jest wymagany.';
    if (ctrl.hasError('min') || ctrl.hasError('max')) return 'Port musi byc w zakresie 1-65535.';
    return null;
  }

  get usernameError(): string | null {
    const ctrl = this.form.get('username')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Adres email jest wymagany.';
    if (ctrl.hasError('email')) return 'Podaj poprawny adres email.';
    return null;
  }

  get pollIntervalError(): string | null {
    const ctrl = this.form.get('pollIntervalSeconds')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required'))
      return this.transloco.translate('supervisor.settings.email.errorPollInterval');
    if (ctrl.hasError('min') || ctrl.hasError('max'))
      return this.transloco.translate('supervisor.settings.email.errorPollRange');
    return null;
  }
}
