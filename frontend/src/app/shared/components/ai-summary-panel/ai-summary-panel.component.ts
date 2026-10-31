import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoModule } from '@jsverse/transloco';
import { catchError, EMPTY, finalize } from 'rxjs';
import {
  AiConfigNotSetError,
  AiServiceUnavailableError,
  AiSummaryService,
  AiSummaryResponse,
} from '../../../features/agent/services/ai-summary.service';

@Component({
  selector: 'app-ai-summary-panel',
  standalone: true,
  imports: [TranslocoModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './ai-summary-panel.component.html',
  styleUrl: './ai-summary-panel.component.scss',
})
export class AiSummaryPanelComponent {
  readonly contactId = input.required<string>();
  readonly showCopyButton = input<boolean>(false);
  readonly hasRecording = input<boolean>(true);
  readonly copyToNotes = output<string>();

  private readonly aiSummaryService = inject(AiSummaryService);
  private readonly destroyRef = inject(DestroyRef);

  readonly collapsed = signal(false);

  readonly aiLoading = signal(false);
  readonly aiSummary = signal<string | null>(null);
  readonly aiModelUsed = signal<string | null>(null);
  readonly aiTokensUsed = signal<number | null>(null);
  /** i18n key of the current error, so the message follows the active language. */
  readonly aiError = signal<string | null>(null);

  onCopyToNotes(): void {
    const summary = this.aiSummary();
    if (summary) this.copyToNotes.emit(summary);
  }

  generateAiSummary(): void {
    if (this.aiLoading()) return;
    this.aiLoading.set(true);
    this.aiError.set(null);
    this.aiSummaryService
      .generateSummary(this.contactId())
      .pipe(
        catchError((err: unknown) => {
          this.aiError.set(this.errorKey(err));
          return EMPTY;
        }),
        finalize(() => this.aiLoading.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((res: AiSummaryResponse) => {
        this.aiSummary.set(res.summary);
        this.aiModelUsed.set(res.modelUsed);
        this.aiTokensUsed.set(res.tokensUsed);
      });
  }

  private errorKey(err: unknown): string {
    if (err instanceof AiConfigNotSetError) return 'agent.aiSummary.errorNotConfigured';
    if (err instanceof AiServiceUnavailableError) return 'agent.aiSummary.errorUnavailable';
    return 'agent.aiSummary.errorUnknown';
  }
}
