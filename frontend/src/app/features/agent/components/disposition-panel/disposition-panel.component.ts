import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnDestroy,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { NgClass } from '@angular/common';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { catchError, EMPTY } from 'rxjs';
import { ContactService } from '../../services/contact.service';
import { AgentStatusService } from '../../services/agent-status.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { DISPOSITION_CODES } from '../../models/disposition.model';
import { AiSummaryPanelComponent } from '../../../../shared/components/ai-summary-panel/ai-summary-panel.component';
import { CustomDispositionService } from '../../../../features/dispositions/services/custom-disposition.service';

interface AvailableDispositionForPanel {
  code: string;
  label: string;
  tone: string;
}

@Component({
  selector: 'app-disposition-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, NgClass, TranslocoModule, AiSummaryPanelComponent],
  templateUrl: './disposition-panel.component.html',
  styleUrl: './disposition-panel.component.scss',
})
export class DispositionPanelComponent implements OnInit, OnDestroy {
  /** ID zakładki/kontaktu – wymagane aby wiedzieć co zapisać */
  readonly contactId = input.required<string>();
  /** Nazwa klienta do wyświetlenia w nagłówku */
  readonly customerName = input<string>('');
  /** Notatka z rozmowy wpisana przez agenta – pre-fills pole notatki */
  readonly prefillNotes = input<string>('');
  /** Emitowane po poprawnym zapisie dyspozycji */
  readonly saved = output<void>();

  private readonly contactService = inject(ContactService);
  private readonly statusService = inject(AgentStatusService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly transloco = inject(TranslocoService);
  private readonly customDispositionService = inject(CustomDispositionService);

  private readonly dialogRef = viewChild.required<ElementRef<HTMLDialogElement>>('dialogEl');

  protected readonly availableDispositions = signal<AvailableDispositionForPanel[]>([]);
  protected readonly dispositionsLoading = signal(false);
  protected readonly dispositionsError = signal<string | null>(null);

  protected readonly selectedCode = signal<string>('');
  protected readonly notes = signal<string>('');
  protected readonly isSaving = signal(false);

  /** ACW timer – sekundy */
  protected readonly acwSeconds = signal(0);
  private acwInterval: ReturnType<typeof setInterval> | null = null;

  protected readonly formattedAcwTime = computed(() => {
    const total = this.acwSeconds();
    const m = Math.floor(total / 60);
    const s = total % 60;
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  });

  protected readonly canSave = computed(() => this.selectedCode().length > 0 && !this.isSaving());

  ngOnInit(): void {
    this.dialogRef().nativeElement.showModal();
    if (this.prefillNotes()) {
      this.notes.set(this.prefillNotes());
    }
    this.startAcwTimer();

    this.dispositionsLoading.set(true);
    this.customDispositionService
      .getAvailableDispositions(this.contactId())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (dispositions) => {
          this.availableDispositions.set(
            dispositions.map((d) => ({ code: d.dispositionCode, label: d.label, tone: d.tone })),
          );
          this.dispositionsLoading.set(false);
        },
        error: (err) => {
          console.warn(
            '[DispositionPanel] Failed to load dispositions from API, using fallback',
            err,
          );
          this.availableDispositions.set(
            DISPOSITION_CODES.map((d) => ({
              code: d.code,
              label: this.transloco.translate(d.labelKey),
              tone: 'neutral',
            })),
          );
          this.dispositionsLoading.set(false);
          this.dispositionsError.set('Używam dyspozycji systemowych (błąd pobierania)');
        },
      });
  }

  ngOnDestroy(): void {
    this.stopAcwTimer();
  }

  private startAcwTimer(): void {
    this.acwSeconds.set(0);
    this.acwInterval = setInterval(() => {
      this.acwSeconds.update((v) => v + 1);
    }, 1000);
  }

  private stopAcwTimer(): void {
    if (this.acwInterval !== null) {
      clearInterval(this.acwInterval);
      this.acwInterval = null;
    }
  }

  protected toneClass(tone: string): string {
    const map: Record<string, string> = {
      positive: 'tone-positive',
      negative: 'tone-negative',
      warning: 'tone-warning',
      neutral: 'tone-neutral',
    };
    return map[tone] ?? 'tone-neutral';
  }

  protected selectCode(code: string): void {
    this.selectedCode.set(code);
  }

  protected onNotesChange(value: string): void {
    this.notes.set(value);
  }

  protected onAiSummaryCopy(summary: string): void {
    const current = this.notes().trim();
    this.notes.set(current ? current + '\n\n' + summary : summary);
  }

  protected save(): void {
    if (!this.canSave()) return;

    this.isSaving.set(true);
    this.stopAcwTimer();

    this.contactService
      .setDisposition(this.contactId(), this.selectedCode(), this.notes())
      .pipe(
        catchError(() => {
          this.notifications.error(this.transloco.translate('agent.disposition.errorSave'));
          this.isSaving.set(false);
          this.startAcwTimer();
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.isSaving.set(false);
        this.notifications.success(this.transloco.translate('agent.disposition.successSave'));
        // Wait for status change confirmation before emitting saved — prevents the tab from
        // closing before the server acknowledges the AVAILABLE status change.
        this.statusService
          .changeStatus('AVAILABLE')
          .pipe(takeUntilDestroyed(this.destroyRef))
          .subscribe({
            next: () => {
              this.notifications.success(
                this.transloco.translate('agent.disposition.statusChanged'),
              );
              this.saved.emit();
            },
            error: () => {
              // Disposition was saved successfully; status change failed.
              // Still close the tab — agent can change status manually.
              this.saved.emit();
            },
          });
      });
  }
}
