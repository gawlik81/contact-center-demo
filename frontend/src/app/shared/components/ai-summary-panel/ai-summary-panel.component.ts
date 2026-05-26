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
import { catchError, EMPTY, finalize } from 'rxjs';
import {
  AiSummaryService,
  AiSummaryResponse,
} from '../../../features/agent/services/ai-summary.service';

@Component({
  selector: 'app-ai-summary-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './ai-summary-panel.component.html',
  styleUrl: './ai-summary-panel.component.scss',
})
export class AiSummaryPanelComponent {
  readonly contactId = input.required<string>();
  readonly showCopyButton = input<boolean>(false);
  readonly copyToNotes = output<string>();

  private readonly aiSummaryService = inject(AiSummaryService);
  private readonly destroyRef = inject(DestroyRef);

  readonly collapsed = signal(false);

  readonly aiLoading = signal(false);
  readonly aiSummary = signal<string | null>(null);
  readonly aiModelUsed = signal<string | null>(null);
  readonly aiTokensUsed = signal<number | null>(null);
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
          this.aiError.set(err instanceof Error ? err.message : 'Nieznany błąd.');
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
}
