import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  computed,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { Subscription } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { NotificationService } from '../../../../../../core/services/notification.service';
import { RetentionService } from '../../../../services/retention.service';
import {
  CampaignArchivePreviewDto,
  CampaignArchiveSettingDto,
} from '../../../../models/retention.model';

export type CampaignArchivePreviewState = 'loading' | 'loaded' | 'error';

/**
 * Sekcja „Okres archiwizacji kontaktów kampanii (dni od zakończenia kampanii)" (FE-113) na
 * stronie „Retencja danych". Ustawienie PER TENANT, osobny zasób `.../retention/campaign-archive`
 * (BE-151) — NIE jest to retencja `CAMPAIGN_DATA` (miesiące, od archiwizacji).
 *
 * Zapis: wydłużenie okresu zapisuje się od razu (nic nie jest archiwizowane wcześniej); SKRÓCENIE
 * otwiera dialog potwierdzenia z podglądem skutku (`previewCampaignArchive`). Błąd podglądu nie
 * blokuje świadomego potwierdzenia, ale jest widoczny. Błędy GET/PUT/preview NIE wywołują toastu
 * globalnego (SKIP_ERROR_TOAST w serwisie) — komunikat jest inline, sukces to jeden toast.
 */
@Component({
  selector: 'app-campaign-archive-setting',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, DatePipe],
  templateUrl: './campaign-archive-setting.component.html',
  styleUrl: './campaign-archive-setting.component.scss',
})
export class CampaignArchiveSettingComponent implements OnInit {
  private readonly retentionService = inject(RetentionService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');
  private readonly saveErrorRef = viewChild<ElementRef<HTMLElement>>('saveErrorEl');
  private previewSub: Subscription | null = null;

  readonly loading = signal(true);
  readonly loadError = signal(false);
  /** 403 na GET: ponawianie nie ma sensu, więc nie pokazujemy przycisku „Ponów". */
  readonly loadForbidden = signal(false);
  readonly setting = signal<CampaignArchiveSettingDto | null>(null);

  /** Trzymane jako string, by reprezentować stan „puste"/nieprawidłowe pole. */
  readonly inputValue = signal('');
  readonly inputTouched = signal(false);
  readonly saving = signal(false);
  /** Klucz i18n (pod `campaignArchive.`) komunikatu błędu zapisu; null = brak błędu. */
  readonly saveErrorKey = signal<string | null>(null);

  // ---- dialog potwierdzenia skrócenia ----
  readonly confirmOpen = signal(false);
  readonly previewState = signal<CampaignArchivePreviewState>('loading');
  readonly preview = signal<CampaignArchivePreviewDto | null>(null);

  // Granice/domyślna są używane dopiero po odczycie ustawienia (szablon renderuje je w
  // gałęzi `setting()`), więc 0 jest tylko neutralnym stanem przed pierwszym odczytem.
  readonly minDays = computed(() => this.setting()?.minDays ?? 0);
  readonly maxDays = computed(() => this.setting()?.maxDays ?? 0);
  readonly defaultDays = computed(() => this.setting()?.defaultDays ?? 0);

  /** Wartość z pola jako liczba, albo null gdy puste / niecałkowite / poza zakresem. */
  readonly parsedValue = computed<number | null>(() => {
    const raw = this.inputValue().trim();
    if (!/^\d+$/.test(raw)) return null;
    const parsed = Number(raw);
    return parsed >= this.minDays() && parsed <= this.maxDays() ? parsed : null;
  });

  readonly isValid = computed(() => this.parsedValue() !== null);
  readonly isDirty = computed(() => {
    const current = this.setting();
    const parsed = this.parsedValue();
    return current !== null && parsed !== current.archiveAfterDays;
  });
  readonly canSave = computed(
    () => this.setting() !== null && this.isValid() && this.isDirty() && !this.saving(),
  );
  /** Walidacja kliencka pokazuje błąd dopiero, gdy użytkownik ruszył pole (dirty-by-text). */
  readonly showValidationError = computed(
    () => this.setting() !== null && !this.isValid() && this.inputTouched(),
  );
  readonly isShortening = computed(() => {
    const current = this.setting();
    const parsed = this.parsedValue();
    return current !== null && parsed !== null && parsed < current.archiveAfterDays;
  });

  /** Skracanie nie dodaje nic ponad to, co i tak zostałoby zarchiwizowane przy obecnym okresie. */
  readonly nothingToArchive = computed(() => {
    const p = this.preview();
    return p !== null && p.additionalCampaigns <= 0 && p.additionalContacts <= 0;
  });

  /** Są kampanie, które czekają na archiwizację już przy obecnym okresie (info pomocnicza). */
  readonly hasPendingAtCurrent = computed(() => {
    const p = this.preview();
    return p !== null && p.campaignsToArchiveAtCurrentDays > 0;
  });

  /** „z X": obecny okres z podglądu (mógł się zmienić po załadowaniu), inaczej z ustawienia. */
  readonly fromDays = computed(
    () => this.preview()?.currentDays ?? this.setting()?.archiveAfterDays ?? null,
  );

  constructor() {
    // Dialog jest renderowany przez @if — otwieramy go jako modalny, gdy tylko trafi do DOM.
    effect(() => {
      const dialog = this.dialogRef()?.nativeElement;
      if (dialog && !dialog.open) {
        dialog.showModal();
      }
    });
    // Błąd zapisu pojawia się po zamknięciu dialogu, często poza ekranem — przenieś tam fokus.
    effect(() => {
      const errorEl = this.saveErrorRef()?.nativeElement;
      if (errorEl) {
        errorEl.focus();
        errorEl.scrollIntoView?.({ block: 'nearest' });
      }
    });
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.loadForbidden.set(false);
    this.retentionService
      .getCampaignArchiveSetting()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (dto) => {
          this.applySetting(dto);
          this.loading.set(false);
        },
        error: (err: unknown) => {
          this.loadForbidden.set(err instanceof HttpErrorResponse && err.status === 403);
          this.loadError.set(true);
          this.loading.set(false);
        },
      });
  }

  onInput(value: string): void {
    this.inputValue.set(value);
    this.inputTouched.set(true);
    this.saveErrorKey.set(null);
  }

  /** Klik „Zapisz": skrócenie -> dialog z podglądem; wydłużenie -> zapis od razu. */
  save(): void {
    if (this.confirmOpen() || this.saving() || !this.canSave()) return;
    if (this.isShortening()) {
      this.openConfirm();
    } else {
      this.persist();
    }
  }

  private openConfirm(): void {
    const days = this.parsedValue();
    if (days === null) return;
    this.previewSub?.unsubscribe();
    this.preview.set(null);
    this.previewState.set('loading');
    this.confirmOpen.set(true);
    this.previewSub = this.retentionService
      .previewCampaignArchive(days)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (dto) => {
          // Odpowiedź dla innego okresu niż aktualnie w polu jest nieaktualna — ignoruj.
          if (dto.newDays !== days || this.parsedValue() !== days) return;
          this.preview.set(dto);
          this.previewState.set('loaded');
        },
        // Błąd podglądu NIE blokuje potwierdzenia — użytkownik widzi błąd + ogólne ostrzeżenie.
        error: () => this.previewState.set('error'),
      });
  }

  confirmShorten(): void {
    if (this.saving()) return;
    this.closeConfirm();
    this.persist();
  }

  cancelConfirm(): void {
    if (this.saving()) return;
    this.closeConfirm();
  }

  private closeConfirm(): void {
    this.previewSub?.unsubscribe();
    this.previewSub = null;
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog?.open) dialog.close();
    this.confirmOpen.set(false);
  }

  onDialogCancel(event: Event): void {
    event.preventDefault();
    this.cancelConfirm();
  }

  private persist(): void {
    const days = this.parsedValue();
    if (days === null || this.saving()) return;
    this.saving.set(true);
    this.saveErrorKey.set(null);
    this.retentionService
      .updateCampaignArchiveSetting(days)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (dto) => {
          this.applySetting(dto);
          this.saving.set(false);
          this.notifications.success(
            this.transloco.translate('supervisor.settings.dataRetention.campaignArchive.saved'),
          );
        },
        error: (err: unknown) => {
          this.saving.set(false);
          this.saveErrorKey.set(this.errorKeyFor(err));
        },
      });
  }

  private errorKeyFor(err: unknown): string {
    const status = err instanceof HttpErrorResponse ? err.status : 0;
    if (status === 422 || status === 400) return 'errorSaveValidation';
    if (status === 403) return 'errorForbidden';
    return 'errorSave';
  }

  private applySetting(dto: CampaignArchiveSettingDto): void {
    this.setting.set(dto);
    this.inputValue.set(String(dto.archiveAfterDays));
    this.inputTouched.set(false);
  }
}
