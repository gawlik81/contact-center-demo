import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { DatePipe } from '@angular/common';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RetentionDataCategory } from '../../../../models/retention.model';

/** Migawka danych karty (Sekcja 2, FE-105) w momencie otwarcia modala — NIE nowe zapytanie do API. */
export interface PurgeConfirmTarget {
  category: RetentionDataCategory;
  /** Przetłumaczona etykieta kategorii, wyliczona przez rodzica (`DataRetentionComponent.categoryLabel`). */
  categoryLabel: string;
  eligibleRowCount: number;
  oldestEligiblePeriod: string | null;
  newestEligiblePeriod: string | null;
}

/**
 * Modal potwierdzenia akcji „Usuń teraz" (FE-106) — CZYSTO PREZENTACYJNY, wzorzec
 * `TenantDeactivateModalComponent`: `input.required<T>()` z danymi do wyświetlenia,
 * `output<void>()` `confirmed`/`cancelled`, BRAK wywołania serwisu w środku (rodzic
 * `DataRetentionComponent` wykonuje `RetentionService.triggerPurge` dopiero po `confirmed`).
 *
 * Purge jest NIEODWRACALNY (jak anonimizacja GDPR, nie jak deaktywacja tenanta), dlatego — inaczej
 * niż `TenantDeactivateModalComponent` — wymaga wpisania frazy potwierdzającej
 * (`confirmPhrase`, tłumaczona per język UI przez i18n: PL `USUŃ`, EN `DELETE`, DE `LÖSCHEN`,
 * UK `ВИДАЛИТИ`; porównanie bez rozróżniania wielkości liter i z `trim`).
 */
@Component({
  selector: 'app-purge-confirm-modal',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, FormsModule, DatePipe],
  templateUrl: './purge-confirm-modal.component.html',
  styleUrl: './purge-confirm-modal.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class PurgeConfirmModalComponent implements AfterViewInit {
  readonly target = input.required<PurgeConfirmTarget>();

  readonly confirmed = output<void>();
  readonly cancelled = output<void>();

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly confirmText = signal('');

  private readonly transloco = inject(TranslocoService);

  /** Expected confirmation phrase in the active UI language (re-emits on language change). */
  readonly confirmPhrase = toSignal(
    this.transloco.selectTranslate<string>(
      'supervisor.settings.dataRetention.purgeModal.confirmPhrase',
    ),
    { initialValue: '' },
  );

  private readonly isPhraseMatching = computed(() => {
    const phrase = this.confirmPhrase().trim().toLocaleLowerCase();
    return phrase.length > 0 && this.confirmText().trim().toLocaleLowerCase() === phrase;
  });

  readonly isConfirmEnabled = () => this.isPhraseMatching();

  ngAfterViewInit(): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
  }

  onEscapeKey(event: Event): void {
    event.preventDefault();
    this.onCancel();
  }

  onConfirm(): void {
    if (!this.isConfirmEnabled()) return;
    this.confirmed.emit();
  }

  onCancel(): void {
    this.cancelled.emit();
  }
}
