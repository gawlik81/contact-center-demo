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
import { catchError, map, of, startWith } from 'rxjs';
import { TranslocoModule } from '@jsverse/transloco';
import {
  AiConfigService,
  AiConfigRequest,
  AiConfigResponse,
  AiProvider,
} from '../../services/ai-config.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { ConfirmDialogComponent } from '../../../../shared/components/confirm-dialog/confirm-dialog.component';

@Component({
  selector: 'app-ai-config',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [ReactiveFormsModule, TranslocoModule, ConfirmDialogComponent],
  templateUrl: './ai-config.component.html',
  styleUrl: './ai-config.component.scss',
})
export class AiConfigComponent implements OnInit {
  private readonly aiConfigService = inject(AiConfigService);
  private readonly notifications = inject(NotificationService);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(true);
  readonly loadError = signal(false);
  readonly submitting = signal(false);
  readonly hasConfig = signal(false);
  readonly showForm = signal(false);
  readonly showDeleteConfirm = signal(false);
  readonly apiKeyMasked = signal<string | null>(null);
  readonly showApiKey = signal(false);
  readonly revealApiKey = signal(false);
  private readonly _formDirty = signal(false);

  readonly providers: { value: AiProvider; label: string }[] = [
    { value: 'ANTHROPIC', label: 'Anthropic (Claude)' },
    { value: 'OPENAI', label: 'OpenAI (GPT)' },
    { value: 'AZURE_OPENAI', label: 'Azure OpenAI' },
    { value: 'OPENROUTER', label: 'OpenRouter.ai' },
  ];

  readonly form = this.fb.group({
    provider: ['ANTHROPIC' as AiProvider, Validators.required],
    apiKey: [''],
    modelName: ['', Validators.required],
    azureEndpoint: [''],
    azureDeploymentName: [''],
    summaryPromptTemplate: [''],
  });

  readonly isAzure = toSignal(
    this.form.get('provider')!.valueChanges.pipe(
      startWith(this.form.get('provider')!.value),
      map((v) => v === 'AZURE_OPENAI'),
    ),
    { initialValue: false },
  );

  private readonly _formValid = toSignal(
    this.form.statusChanges.pipe(
      startWith(this.form.status),
      map((s) => s === 'VALID'),
    ),
  );

  readonly isSaveDisabled = computed(
    () => this.submitting() || !this._formDirty() || !this._formValid(),
  );

  ngOnInit(): void {
    this.loadConfig();
    this.form.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this._formDirty.set(this.form.dirty);
    });
  }

  private loadConfig(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.aiConfigService
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
          this.patchForm(config);
        } else {
          this.hasConfig.set(false);
          this.showForm.set(false);
        }
        this.loading.set(false);
      });
  }

  private patchForm(config: AiConfigResponse): void {
    this.apiKeyMasked.set(config.apiKeyMasked);

    // When editing, apiKey is not required — backend preserves existing value if empty is sent
    this.form.get('apiKey')!.clearValidators();
    this.form.get('apiKey')!.updateValueAndValidity({ emitEvent: false });

    this.form.patchValue({
      provider: config.provider,
      apiKey: '',
      modelName: config.modelName,
      azureEndpoint: config.azureEndpoint ?? '',
      azureDeploymentName: config.azureDeploymentName ?? '',
      summaryPromptTemplate: config.summaryPromptTemplate ?? '',
    });
    this.form.markAsPristine();
    this.form.markAsUntouched();
    this._formDirty.set(false);
  }

  onSetupClick(): void {
    this.showForm.set(true);
    this.form.reset({ provider: 'ANTHROPIC' });
    this._formDirty.set(false);
    this.apiKeyMasked.set(null);
    this.showApiKey.set(false);
    this.revealApiKey.set(false);

    // apiKey required when creating new config
    this.form.get('apiKey')!.addValidators(Validators.required);
    this.form.get('apiKey')!.updateValueAndValidity();
  }

  onCancelSetup(): void {
    if (this.hasConfig()) {
      this.loadConfig();
      this.showApiKey.set(false);
      this.revealApiKey.set(false);
    } else {
      this.showForm.set(false);
      this.form.reset();
      this._formDirty.set(false);
    }
  }

  onUnlockApiKey(): void {
    this.showApiKey.set(true);
    // No required validator when editing — backend preserves existing value if empty is sent
    this.form.get('apiKey')!.markAsDirty();
  }

  onSubmit(): void {
    // When editing an existing config, apiKey is optional — backend preserves existing value.
    if (this.hasConfig()) {
      this.form.get('apiKey')!.clearValidators();
      this.form.get('apiKey')!.updateValueAndValidity({ emitEvent: false });
    }

    this.form.markAllAsTouched();
    if (this.form.invalid || this.submitting()) return;

    this.submitting.set(true);
    const raw = this.form.getRawValue();
    const request: AiConfigRequest = {
      provider: raw.provider as AiProvider,
      apiKey: raw.apiKey?.trim() || null,
      modelName: raw.modelName!.trim(),
      azureEndpoint: raw.azureEndpoint?.trim() || null,
      azureDeploymentName: raw.azureDeploymentName?.trim() || null,
      summaryPromptTemplate: raw.summaryPromptTemplate?.trim() || null,
    };

    this.aiConfigService
      .saveConfig(request)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (saved) => {
          this.submitting.set(false);
          this.hasConfig.set(true);
          this.showApiKey.set(false);
          this.revealApiKey.set(false);
          this.patchForm(saved);
          this.notifications.success('Konfiguracja AI zapisana.');
        },
        error: () => {
          this.submitting.set(false);
          this.notifications.error('Błąd zapisu konfiguracji AI.');
        },
      });
  }

  onDelete(): void {
    this.showDeleteConfirm.set(true);
  }

  onDeleteConfirmed(): void {
    this.showDeleteConfirm.set(false);
    this.aiConfigService
      .deleteConfig()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => {
          this.hasConfig.set(false);
          this.showForm.set(false);
          this.form.reset();
          this._formDirty.set(false);
          this.apiKeyMasked.set(null);
          this.notifications.success('Konfiguracja AI usunięta.');
        },
        error: () => this.notifications.error('Błąd usuwania konfiguracji AI.'),
      });
  }

  onDeleteCancelled(): void {
    this.showDeleteConfirm.set(false);
  }

  get modelNameError(): string | null {
    const ctrl = this.form.get('modelName')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Nazwa modelu jest wymagana.';
    return null;
  }

  get apiKeyError(): string | null {
    // When editing an existing config, API Key is never required
    if (this.hasConfig()) return null;
    const ctrl = this.form.get('apiKey')!;
    if (!ctrl.invalid || (!ctrl.dirty && !ctrl.touched)) return null;
    if (ctrl.hasError('required')) return 'Klucz API jest wymagany.';
    return null;
  }
}
