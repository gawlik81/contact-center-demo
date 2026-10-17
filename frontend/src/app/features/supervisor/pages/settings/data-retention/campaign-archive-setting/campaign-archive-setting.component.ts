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
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { NotificationService } from '../../../../../../core/services/notification.service';
import { RetentionService } from '../../../../services/retention.service';
import {
  CampaignArchivePreviewDto,
  CampaignArchiveSettingDto,
} from '../../../../models/retention.model';

/** Wartości z BE-151, używane do walidacji do czasu odczytu `minDays`/`maxDays` z API. */
const FALLBACK_MIN_DAYS = 30;
const FALLBACK_MAX_DAYS = 730;
const FALLBACK_DEFAULT_DAYS = 180;

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

  readonly loading = signal(true);
  readonly loadError = signal(false);
  readonly setting = signal<CampaignArchiveSettingDto | null>(null);

  /** Trzymane jako string, by reprezentować stan „puste"/nieprawidłowe pole. */
  readonly inputValue = signal('');
  readonly saving = signal(false);
  /** Klucz i18n (pod `campaignArchive.`) komunikatu błędu zapisu; null = brak błędu. */
  readonly saveErrorKey = signal<string | null>(null);

  // ---- dialog potwierdzenia skrócenia ----
  readonly confirmOpen = signal(false);
  readonly previewState = signal<CampaignArchivePreviewState>('loading');
  readonly preview = signal<CampaignArchivePreviewDto | null>(null);

  readonly minDays = computed(() => this.setting()?.minDays ?? FALLBACK_MIN_DAYS);
  readonly maxDays = computed(() => this.setting()?.maxDays ?? FALLBACK_MAX_DAYS);
  readonly defaultDays = computed(() => this.setting()?.defaultDays ?? FALLBACK_DEFAULT_DAYS);

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
  readonly inputTouched = signal(false);

  readonly isShortening = computed(() => {
    const current = this.setting();
    const parsed = this.parsedValue();
    return current !== null && parsed !== null && parsed < current.archiveAfterDays;
  });

  readonly nothingToArchive = computed(() => {
    const p = this.preview();
    return p !== null && p.campaignsToArchive === 0 && p.contactsToArchive === 0;
  });

  constructor() {
    // Dialog jest renderowany przez @if — otwieramy go jako modalny, gdy tylko trafi do DOM.
    effect(() => {
      const dialog = this.dialogRef()?.nativeElement;
      if (dialog && !dialog.open) {
        dialog.showModal();
      }
    });
  }

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.retentionService
      .getCampaignArchiveSetting()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (dto) => {
          this.applySetting(dto);
          this.loading.set(false);
        },
        error: () => {
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
    if (!this.canSave()) return;
    if (this.isShortening()) {
      this.openConfirm();
    } else {
      this.persist();
    }
  }

  private openConfirm(): void {
    const days = this.parsedValue();
    if (days === null) return;
    this.preview.set(null);
    this.previewState.set('loading');
    this.confirmOpen.set(true);
    this.retentionService
      .previewCampaignArchive(days)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (dto) => {
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
