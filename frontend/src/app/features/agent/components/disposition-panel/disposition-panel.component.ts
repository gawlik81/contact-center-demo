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
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { catchError, EMPTY } from 'rxjs';
import { ContactService } from '../../services/contact.service';
import { AgentStatusService } from '../../services/agent-status.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { DISPOSITION_CODES, DispositionCode } from '../../models/disposition.model';

@Component({
  selector: 'app-disposition-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, TranslocoModule],
  templateUrl: './disposition-panel.component.html',
  styleUrl: './disposition-panel.component.scss',
})
export class DispositionPanelComponent implements OnInit, OnDestroy {
  /** ID zakładki/kontaktu – wymagane aby wiedzieć co zapisać */
  readonly contactId = input.required<string>();
  /** Nazwa klienta do wyświetlenia w nagłówku */
  readonly customerName = input<string>('');
  /** Emitowane po poprawnym zapisie dyspozycji */
  readonly saved = output<void>();

  private readonly contactService = inject(ContactService);
  private readonly statusService = inject(AgentStatusService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly transloco = inject(TranslocoService);

  private readonly dialogRef = viewChild.required<ElementRef<HTMLDialogElement>>('dialogEl');

  protected readonly dispositionCodes: DispositionCode[] = DISPOSITION_CODES;

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
    this.startAcwTimer();
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

  protected selectCode(code: string): void {
    this.selectedCode.set(code);
  }

  protected onNotesChange(value: string): void {
    this.notes.set(value);
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
