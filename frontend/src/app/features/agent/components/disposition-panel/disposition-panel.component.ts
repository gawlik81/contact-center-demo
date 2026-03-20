import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { catchError, EMPTY } from 'rxjs';
import { ContactService } from '../../services/contact.service';
import { AgentStatusService } from '../../services/agent-status.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { DISPOSITION_CODES, DispositionCode } from '../../models/disposition.model';

@Component({
  selector: 'app-disposition-panel',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  templateUrl: './disposition-panel.component.html',
  styleUrl: './disposition-panel.component.scss',
})
export class DispositionPanelComponent implements OnInit {
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
    this.startAcwTimer();
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

  protected onCodeChange(value: string): void {
    this.selectedCode.set(value);
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
        takeUntilDestroyed(this.destroyRef),
        catchError(() => {
          this.notifications.error('Nie udalo sie zapisac dyspozycji. Sprobuj ponownie.');
          this.isSaving.set(false);
          this.startAcwTimer();
          return EMPTY;
        }),
      )
      .subscribe(() => {
        this.isSaving.set(false);
        this.statusService.changeStatus('AVAILABLE');
        this.notifications.success('Dyspozycja zapisana. Status zmieniony na Dostepny.');
        this.saved.emit();
      });
  }
}
