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
import { NotificationService } from '../../../../../core/services/notification.service';
import { RetentionService } from '../../../services/retention.service';
import { RetentionDataCategory, RetentionSummaryDto } from '../../../models/retention.model';

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

interface PolicyRowState {
  category: RetentionDataCategory;
  /** Trzymane jako string (nie number) żeby dało się reprezentować stan "puste"/nieprawidłowe pole. */
  retentionMonthsInput: string;
  autoPurgeEnabled: boolean;
}

/**
 * Strona „Ustawienia > Retencja danych" (EPIC-29, FE-104/FE-105). Sekcja 1: tabela konfiguracji
 * 4 polityk retencji (jeden wiersz per {@link RetentionDataCategory}). Sekcja 2 (FE-105):
 * dashboard „dane kwalifikujące się do usunięcia" — 4 karty czytające WYŁĄCZNIE z cache
 * (`GET .../summary`, wypełniany przez `RetentionEvaluationJob`, BE-112), bez liczenia niczego
 * na żywo. Kolejne sekcje (historia purge, przycisk „usuń teraz") dochodzą w FE-106/107 jako
 * kolejne `<section>` tej samej strony — wzorzec z EPIC-28 (FE-098 → FE-101/102).
 *
 * Tylko rola ADMIN — wymuszone przez `roleGuard` w routingu, nie w tym komponencie.
 */
@Component({
  selector: 'app-data-retention',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, DatePipe],
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
}
