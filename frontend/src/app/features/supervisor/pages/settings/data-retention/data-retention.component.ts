import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subscription, catchError, interval, of, switchMap } from 'rxjs';
import { NotificationService } from '../../../../../core/services/notification.service';
import { RetentionService } from '../../../services/retention.service';
import { RetentionDataCategory, RetentionSummaryDto } from '../../../models/retention.model';
import {
  PurgeConfirmModalComponent,
  PurgeConfirmTarget,
} from './purge-confirm-modal/purge-confirm-modal.component';

/** Zgodne z CHECK constraint `tenant_retention_policy.retention_months` (V082, DB-046). */
const MIN_RETENTION_MONTHS = 1;
const MAX_RETENTION_MONTHS = 120;

/**
 * Fallback użyty TYLKO gdy backend nie zwróci polityki dla którejś z 4 kategorii (nie powinno
 * się zdarzyć w praktyce — polityki są seedowane per tenant — ale `updatePolicy` jest upsertem,
 * więc formularz musi umieć wystartować bez istniejącego `policyId`).
 */
const DEFAULT_RETENTION_MONTHS = 12;

/** Kolejność wierszy tabeli — zgodna z sortowaniem `data_category ASC` po stronie backendu. */
const CATEGORY_ORDER: RetentionDataCategory[] = [
  'CONTACT_INTERACTIONS',
  'RECORDINGS',
  'TRANSCRIPTS',
  'CAMPAIGN_DATA',
];

/**
 * `RetentionPurgeService.purge()` (backend) rzuca `UnsupportedOperationException` (HTTP 501) dla
 * tych dwóch kategorii — obsługa to przyszłe BE-116 (RECORDINGS → `RecordingRetentionJob`) i
 * BE-119 (CAMPAIGN_DATA → `purge_campaign_contact_archive`), jeszcze nieukończone. Przycisk „Usuń
 * teraz" jest dla nich disabled (z tooltipem wyjaśniającym), nie ukryty — użytkownik widzi kartę i
 * `eligibleRowCount` (o ile `computed`), ale nie może wywołać akcji gwarantowanie kończącej się
 * błędem.
 */
const UNSUPPORTED_PURGE_CATEGORIES: ReadonlySet<RetentionDataCategory> = new Set([
  'RECORDINGS',
  'CAMPAIGN_DATA',
]);

/** Interwał odpytywania statusu purge — 1:1 z `POLLING_INTERVAL_MS` w `CampaignImportComponent`. */
const PURGE_POLLING_INTERVAL_MS = 3_000;

interface PolicyRowState {
  category: RetentionDataCategory;
  /** Trzymane jako string (nie number) żeby dało się reprezentować stan "puste"/nieprawidłowe pole. */
  retentionMonthsInput: string;
  autoPurgeEnabled: boolean;
}

/**
 * Strona „Ustawienia > Retencja danych" (EPIC-29, FE-104/FE-105/FE-106). Sekcja 1: tabela
 * konfiguracji 4 polityk retencji (jeden wiersz per {@link RetentionDataCategory}). Sekcja 2
 * (FE-105): dashboard „dane kwalifikujące się do usunięcia" — 4 karty czytające WYŁĄCZNIE z cache
 * (`GET .../summary`, wypełniany przez `RetentionEvaluationJob`, BE-112), bez liczenia niczego
 * na żywo. Przycisk „Usuń teraz" na kartach Sekcji 2 (FE-106) otwiera
 * {@link PurgeConfirmModalComponent} z migawką danych karty, po potwierdzeniu wywołuje
 * `RetentionService.triggerPurge` (async, 202) i odpytuje `getPurgeStatus` do stanu terminalnego —
 * patrz `triggerPurgeForCategory`/`startPurgePolling` niżej. Sekcja historii purge dochodzi w
 * FE-107 jako kolejny `<section>` tej samej strony — wzorzec z EPIC-28 (FE-098 → FE-101/102).
 *
 * Tylko rola ADMIN — wymuszone przez `roleGuard` w routingu, nie w tym komponencie.
 */
@Component({
  selector: 'app-data-retention',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, DatePipe, PurgeConfirmModalComponent],
  templateUrl: './data-retention.component.html',
  styleUrl: './data-retention.component.scss',
})
export class DataRetentionComponent implements OnInit {
  private readonly retentionService = inject(RetentionService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  // ---- Sekcja 1: tabela polityk ----

  readonly loading = signal(true);
  readonly loadError = signal(false);
  readonly rows = signal<PolicyRowState[]>([]);

  /** Per-wiersz stan zapisu (Set kategorii aktualnie zapisywanych), analogicznie do `pendingActionIds` w PluginsPageComponent. */
  readonly pendingCategories = signal<Set<RetentionDataCategory>>(new Set());

  // ---- Sekcja 2: dashboard "dane kwalifikujące się do usunięcia" (FE-105) ----
  //
  // Czyta WYŁĄCZNIE z cache (`GET .../summary`, tabela `tenant_retention_pending_summary`
  // wypełniana przez `RetentionEvaluationJob`, BE-112) — zero liczenia na żywo, zgodnie z
  // wymogiem dokumentu projektowego. Stan ładowania/błędu ODDZIELNY od Sekcji 1 (`summaryLoading`/
  // `summaryLoadError` zamiast `loading`/`loadError`) — obie sekcje muszą móc ładować się i
  // failować niezależnie, patrz `ngOnInit`.

  readonly summaryLoading = signal(true);
  readonly summaryLoadError = signal(false);
  readonly summaries = signal<RetentionSummaryDto[]>([]);

  // ---- Akcja "Usuń teraz" (FE-106) ----
  //
  // Modal potwierdzenia jest CZYSTO PREZENTACYJNY (wzorzec TenantDeactivateModalComponent) —
  // cała logika wywołania triggerPurge + pollingu żyje TU, w rodzicu, nie w modalu.

  /** Migawka danych karty przekazana do modala; null gdy modal zamknięty. */
  readonly purgeModalTarget = signal<PurgeConfirmTarget | null>(null);

  /** Kategorie z aktualnie trwającym purge — analogiczne do `pendingCategories` z Sekcji 1. */
  readonly purgingCategories = signal<Set<RetentionDataCategory>>(new Set());

  /**
   * Subskrypcje pollingu per kategoria — pozwala zatrzymać jawnie po `COMPLETED`/`FAILED` zamiast
   * polegać wyłącznie na `takeUntilDestroyed` (ulepszenie względem `CampaignImportComponent.
   * startPolling`, który nie zatrzymuje `interval()` po stanie terminalnym i dla długo otwartej
   * strony leci w nieskończoność mimo zakończonego joba).
   */
  private readonly purgeSubscriptions = new Map<RetentionDataCategory, Subscription>();

  ngOnInit(): void {
    // Wywołania NIEZALEŻNE i RÓWNOLEGŁE — żadne nie czeka na drugie (wzorzec
    // PluginsPageComponent.ngOnInit: loadInstallations() + loadCatalog()).
    this.loadPolicies();
    this.loadSummary();
  }

  loadSummary(): void {
    this.summaryLoading.set(true);
    this.summaryLoadError.set(false);
    this.retentionService
      .getSummary()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (summaries) => {
          const byCategory = new Map(summaries.map((s) => [s.dataCategory, s]));
          // Defensywny fallback brakującej kategorii — analogiczny do rows() w Sekcji 1.
          // Backend dokumentuje "ZAWSZE dokładnie 4 wpisy", ale gdyby jednak zwrócił mniej,
          // brakująca kategoria renderuje się w stanie "jeszcze nie przeliczono" (computed: false)
          // zamiast znikać z dashboardu.
          this.summaries.set(
            CATEGORY_ORDER.map(
              (category) =>
                byCategory.get(category) ?? {
                  dataCategory: category,
                  eligibleRowCount: 0,
                  oldestEligiblePeriod: null,
                  newestEligiblePeriod: null,
                  computedAt: null,
                  computed: false,
                },
            ),
          );
          this.summaryLoading.set(false);
        },
        error: () => {
          this.summaryLoadError.set(true);
          this.summaryLoading.set(false);
        },
      });
  }

  loadPolicies(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.retentionService
      .listPolicies()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (policies) => {
          const byCategory = new Map(policies.map((p) => [p.dataCategory, p]));
          this.rows.set(
            CATEGORY_ORDER.map((category) => {
              const existing = byCategory.get(category);
              return {
                category,
                retentionMonthsInput: String(existing?.retentionMonths ?? DEFAULT_RETENTION_MONTHS),
                autoPurgeEnabled: existing?.autoPurgeEnabled ?? false,
              };
            }),
          );
          this.loading.set(false);
        },
        error: () => {
          this.loadError.set(true);
          this.loading.set(false);
        },
      });
  }

  categoryLabel(category: RetentionDataCategory): string {
    return this.transloco.translate('supervisor.settings.dataRetention.category.' + category);
  }

  private parsedRetentionMonths(row: PolicyRowState): number {
    return Number(row.retentionMonthsInput.trim());
  }

  isRowValid(row: PolicyRowState): boolean {
    const raw = row.retentionMonthsInput.trim();
    if (raw === '') return false;
    const parsed = Number(raw);
    return (
      Number.isInteger(parsed) && parsed >= MIN_RETENTION_MONTHS && parsed <= MAX_RETENTION_MONTHS
    );
  }

  isPending(category: RetentionDataCategory): boolean {
    return this.pendingCategories().has(category);
  }

  private setPending(category: RetentionDataCategory, pending: boolean): void {
    this.pendingCategories.update((set) => {
      const next = new Set(set);
      if (pending) next.add(category);
      else next.delete(category);
      return next;
    });
  }

  updateRetentionMonthsInput(category: RetentionDataCategory, value: string): void {
    this.rows.update((rows) =>
      rows.map((row) =>
        row.category === category ? { ...row, retentionMonthsInput: value } : row,
      ),
    );
  }

  toggleAutoPurge(category: RetentionDataCategory): void {
    if (this.isPending(category)) return;
    this.rows.update((rows) =>
      rows.map((row) =>
        row.category === category ? { ...row, autoPurgeEnabled: !row.autoPurgeEnabled } : row,
      ),
    );
  }

  savePolicy(row: PolicyRowState): void {
    if (!this.isRowValid(row) || this.isPending(row.category)) return;

    const retentionMonths = this.parsedRetentionMonths(row);
    this.setPending(row.category, true);
    this.retentionService
      .updatePolicy(row.category, retentionMonths, row.autoPurgeEnabled)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (dto) => {
          this.setPending(row.category, false);
          this.rows.update((rows) =>
            rows.map((r) =>
              r.category === row.category
                ? {
                    ...r,
                    retentionMonthsInput: String(dto.retentionMonths),
                    autoPurgeEnabled: dto.autoPurgeEnabled,
                  }
                : r,
            ),
          );
          this.notifications.success(
            this.transloco.translate('supervisor.settings.dataRetention.successSave'),
          );
        },
        error: () => {
          this.setPending(row.category, false);
          this.notifications.error(
            this.transloco.translate('supervisor.settings.dataRetention.errorSave'),
          );
        },
      });
  }

  // ---- Akcja "Usuń teraz" (FE-106) ----

  /** RECORDINGS/CAMPAIGN_DATA: backend rzuca 501, patrz {@link UNSUPPORTED_PURGE_CATEGORIES}. */
  isPurgeUnsupported(category: RetentionDataCategory): boolean {
    return UNSUPPORTED_PURGE_CATEGORIES.has(category);
  }

  isPurging(category: RetentionDataCategory): boolean {
    return this.purgingCategories().has(category);
  }

  /** Disabled gdy: nic nie wiadomo, nic do usunięcia, kategoria nieobsługiwana, albo purge już trwa. */
  isPurgeDisabled(entry: RetentionSummaryDto): boolean {
    return (
      !entry.computed ||
      entry.eligibleRowCount === 0 ||
      this.isPurgeUnsupported(entry.dataCategory) ||
      this.isPurging(entry.dataCategory)
    );
  }

  private setPurging(category: RetentionDataCategory, purging: boolean): void {
    this.purgingCategories.update((set) => {
      const next = new Set(set);
      if (purging) next.add(category);
      else next.delete(category);
      return next;
    });
  }

  /**
   * Otwiera modal potwierdzenia z migawką AKTUALNEGO stanu karty (`entry` pochodzi z `summaries()`
   * już wczytanego przez FE-105) — celowo NIE odpytujemy `getSummary()` ponownie tutaj, kryterium
   * akceptacji FE-106 tego wprost wymaga.
   */
  openPurgeModal(entry: RetentionSummaryDto): void {
    if (this.isPurgeDisabled(entry)) return;
    this.purgeModalTarget.set({
      category: entry.dataCategory,
      categoryLabel: this.categoryLabel(entry.dataCategory),
      eligibleRowCount: entry.eligibleRowCount,
      oldestEligiblePeriod: entry.oldestEligiblePeriod,
      newestEligiblePeriod: entry.newestEligiblePeriod,
    });
  }

  closePurgeModal(): void {
    this.purgeModalTarget.set(null);
  }

  /** `(confirmed)` z modala — modal sam NIE woła serwisu, cała logika żyje tutaj (patrz komentarz klasy). */
  onPurgeConfirmed(): void {
    const target = this.purgeModalTarget();
    if (!target) return;
    this.closePurgeModal();
    this.triggerPurgeForCategory(target.category, target.categoryLabel);
  }

  private triggerPurgeForCategory(category: RetentionDataCategory, categoryLabel: string): void {
    this.setPurging(category, true);
    this.retentionService
      .triggerPurge(category)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ purgeId }) => {
          // Toast "operacja rozpoczęta" — WYRAŹNIE nie "zakończona": triggerPurge zwraca purgeId
          // natychmiast (202 Accepted), wynik dociera później przez polling startPurgePolling().
          this.notifications.success(
            this.transloco.translate('supervisor.settings.dataRetention.purgeStarted', {
              category: categoryLabel,
            }),
          );
          this.startPurgePolling(category, purgeId);
        },
        error: () => {
          this.setPurging(category, false);
          this.notifications.error(
            this.transloco.translate('supervisor.settings.dataRetention.purgeErrorGeneric'),
          );
        },
      });
  }

  /**
   * Odpytuje `getPurgeStatus(purgeId)` co {@link PURGE_POLLING_INTERVAL_MS} do stanu `COMPLETED`/
   * `FAILED`. UWAGA — w odróżnieniu od `CampaignImportComponent.startPolling` (wzorzec źródłowy),
   * `interval()` jest tu zatrzymywany JAWNIE (`subscription.unsubscribe()`) w momencie osiągnięcia
   * stanu terminalnego, a nie tylko przy zniszczeniu komponentu — inaczej dla długo otwartej
   * strony zbędne zapytania leciałyby w nieskończoność mimo że purge już dawno się skończył.
   */
  private startPurgePolling(category: RetentionDataCategory, purgeId: string): void {
    // Defensywnie zamknij ewentualną poprzednią subskrypcję dla tej kategorii — nie powinno się
    // zdarzyć (przycisk jest disabled przez isPurging()), ale unikamy dwóch równoległych pollingów.
    this.purgeSubscriptions.get(category)?.unsubscribe();

    const subscription = interval(PURGE_POLLING_INTERVAL_MS)
      .pipe(
        switchMap(() =>
          this.retentionService.getPurgeStatus(purgeId).pipe(catchError(() => of(null))),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((result) => {
        if (!result || result.status === 'RUNNING') return;

        this.purgeSubscriptions.get(category)?.unsubscribe();
        this.purgeSubscriptions.delete(category);
        this.setPurging(category, false);

        if (result.status === 'COMPLETED') {
          this.notifications.success(
            this.transloco.translate('supervisor.settings.dataRetention.purgeSuccess', {
              count: result.rowsDeleted ?? 0,
            }),
          );
          // Odśwież dashboard Sekcji 2 — pokaże zaktualizowany/zerowy eligibleRowCount.
          // FE-107 dołoży tu analogiczne odświeżenie sekcji historii purge (jeszcze nieistniejącej
          // na tej stronie — poza zakresem FE-106).
          this.loadSummary();
        } else {
          this.notifications.error(
            result.errorMessage
              ? this.transloco.translate('supervisor.settings.dataRetention.purgeError', {
                  message: result.errorMessage,
                })
              : this.transloco.translate('supervisor.settings.dataRetention.purgeErrorGeneric'),
          );
        }
      });

    this.purgeSubscriptions.set(category, subscription);
  }
}
