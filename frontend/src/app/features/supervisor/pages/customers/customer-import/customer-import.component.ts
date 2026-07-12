import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { interval, switchMap, catchError, of } from 'rxjs';
import { CustomerService } from '../services/customer.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { DeduplicationMode, ImportJobStatus } from '../customer-import.model';

export type ImportStep = 'upload' | 'mapping' | 'progress' | 'report';

export type SystemField =
  | 'phone'
  | 'first_name'
  | 'last_name'
  | 'external_id'
  | 'email'
  | 'custom_fields'
  | 'consent_given'
  | 'marketing_consent'
  | 'skip';

export interface ColumnMapping {
  csvHeader: string;
  systemField: SystemField;
  /** Target key name in custom_fields; only relevant when systemField === 'custom_fields'. */
  customFieldKey?: string;
}

const MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024; // 50 MB
const POLLING_INTERVAL_MS = 3_000;
const PREVIEW_ROWS = 5;

@Component({
  selector: 'app-customer-import',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, FormsModule],
  templateUrl: './customer-import.component.html',
  styleUrl: './customer-import.component.scss',
})
export class CustomerImportComponent {
  private readonly customerService = inject(CustomerService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  // ── Step state ────────────────────────────────────────────────────────────
  readonly currentStep = signal<ImportStep>('upload');
  readonly allSteps = computed<ImportStep[]>(() =>
    this.importFormat() === 'json'
      ? ['upload', 'progress', 'report']
      : ['upload', 'mapping', 'progress', 'report'],
  );

  // ── Step 1: Upload ────────────────────────────────────────────────────────
  readonly selectedFile = signal<File | null>(null);
  readonly importFormat = signal<'csv' | 'json'>('csv');
  readonly deduplicationMode = signal<DeduplicationMode>('SKIP');
  readonly columnSeparator = signal<string>(',');
  readonly quoteChar = signal<string>('"');
  readonly fileError = signal<string | null>(null);
  readonly isDragging = signal(false);
  readonly previewRows = signal<string[][]>([]);
  readonly previewHeaders = signal<string[]>([]);

  readonly columnSeparatorOptions: { value: string; label: string }[] = [
    { value: ',', label: ', (przecinek)' },
    { value: ';', label: '; (sredník)' },
    { value: '\t', label: 'Tab (tabulator)' },
    { value: '|', label: '| (pipe)' },
  ];

  readonly quoteCharOptions: { value: string; label: string }[] = [
    { value: '"', label: '" (cudzyslów podwójny)' },
    { value: "'", label: "' (apostrof)" },
    { value: '', label: '(brak)' },
  ];

  // ── Step 2: Mapping ───────────────────────────────────────────────────────
  readonly columnMappings = signal<ColumnMapping[]>([]);
  readonly mappingError = signal<string | null>(null);

  readonly systemFieldOptions: { value: SystemField; label: string }[] = [
    { value: 'phone', label: 'Telefon (wymagany)' },
    { value: 'first_name', label: 'Imie' },
    { value: 'last_name', label: 'Nazwisko' },
    { value: 'external_id', label: 'ID zewnetrzne' },
    { value: 'email', label: 'Email' },
    { value: 'custom_fields', label: 'Pole dodatkowe' },
    { value: 'consent_given', label: 'Zgoda na przetwarzanie danych' },
    { value: 'marketing_consent', label: 'Zgoda marketingowa' },
    { value: 'skip', label: '(Pomin)' },
  ];

  readonly isPhoneMapped = computed(() =>
    this.columnMappings().some((m) => m.systemField === 'phone'),
  );

  // ── Step 3: Progress ──────────────────────────────────────────────────────
  readonly jobStatus = signal<{
    jobId: string;
    status: ImportJobStatus;
    processed: number;
    total: number;
    imported: number;
    updated: number;
    skipped: number;
    failed: number;
    errorFileAvailable: boolean;
  } | null>(null);

  readonly progressPercent = computed(() => {
    const job = this.jobStatus();
    if (!job || job.total === 0) return 0;
    return Math.round((job.processed / job.total) * 100);
  });

  readonly submitting = signal(false);

  // ── Constructor ──────────────────────────────────────────────────────────
  constructor() {
    effect(() => {
      const format = this.importFormat();
      const file = this.selectedFile();
      if (!file) return;

      if (format === 'json') {
        this.parseJsonPreview(file);
      } else {
        this.columnSeparator();
        this.quoteChar();
        this.parseCsvPreview(file);
      }
    });
  }

  // ── Step 1 handlers ──────────────────────────────────────────────────────

  onFileInputChange(event: Event): void {
    const el = event.target as HTMLInputElement;
    const file = el.files?.[0] ?? null;
    this.handleFile(file);
  }

  onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.isDragging.set(true);
  }

  onDragLeave(): void {
    this.isDragging.set(false);
  }

  onDrop(event: DragEvent): void {
    event.preventDefault();
    this.isDragging.set(false);
    const file = event.dataTransfer?.files?.[0] ?? null;
    this.handleFile(file);
  }

  private handleFile(file: File | null): void {
    this.fileError.set(null);
    if (!file) return;

    const lowerName = file.name.toLowerCase();
    const isCsv = lowerName.endsWith('.csv');
    const isJson = lowerName.endsWith('.json');

    if (!isCsv && !isJson) {
      this.fileError.set('Dozwolone sa tylko pliki CSV (.csv) lub JSON (.json).');
      this.selectedFile.set(null);
      return;
    }

    if (file.size > MAX_FILE_SIZE_BYTES) {
      this.fileError.set(
        `Plik jest za duzy (${this.formatFileSize(file.size)}). Maksymalny rozmiar to 50 MB.`,
      );
      this.selectedFile.set(null);
      return;
    }

    this.importFormat.set(isJson ? 'json' : 'csv');
    this.selectedFile.set(file);
    if (isJson) {
      this.parseJsonPreview(file);
    } else {
      this.parseCsvPreview(file);
    }
  }

  private parseCsvPreview(file: File): void {
    const sep = this.columnSeparator();
    const quote = this.quoteChar();
    const reader = new FileReader();
    reader.onload = (e) => {
      const text = (e.target?.result as string) ?? '';
      const lines = text
        .split(/\r?\n/)
        .map((l) => l.trim())
        .filter((l) => l.length > 0);

      if (lines.length === 0) {
        this.previewHeaders.set([]);
        this.previewRows.set([]);
        return;
      }

      const allRows = lines.map((l) => this.parseCSVLine(l, sep, quote));
      const firstRow = allRows[0];

      const looksLikeHeader = firstRow.every((cell) => isNaN(Number(cell)) && cell.trim() !== '');
      let headers: string[];
      let dataRows: string[][];

      if (looksLikeHeader) {
        headers = firstRow;
        dataRows = allRows.slice(1, PREVIEW_ROWS + 1);
      } else {
        headers = firstRow.map((_, i) => `Kolumna ${i + 1}`);
        dataRows = allRows.slice(0, PREVIEW_ROWS);
      }

      this.previewHeaders.set(headers);
      this.previewRows.set(dataRows);
      this.buildInitialMappings(headers);
    };
    reader.readAsText(file);
  }

  private parseJsonPreview(file: File): void {
    const reader = new FileReader();
    reader.onload = (e) => {
      const text = (e.target?.result as string) ?? '';

      let parsed: unknown;
      try {
        parsed = JSON.parse(text);
      } catch {
        this.setInvalidJsonState();
        return;
      }

      const isArrayOfObjects =
        Array.isArray(parsed) &&
        parsed.every((item) => typeof item === 'object' && item !== null && !Array.isArray(item));

      if (!isArrayOfObjects) {
        this.setInvalidJsonState();
        return;
      }

      const records = parsed as Record<string, unknown>[];
      if (records.length === 0) {
        this.setInvalidJsonState('supervisor.customerImport.emptyJsonArrayError');
        return;
      }

      const sample = records.slice(0, PREVIEW_ROWS);
      const headerSet = new Set<string>();
      sample.forEach((record) => Object.keys(record).forEach((key) => headerSet.add(key)));
      const headers = Array.from(headerSet);

      const rows = sample.map((record) =>
        headers.map((header) => this.stringifyPreviewCell(record[header])),
      );

      this.previewHeaders.set(headers);
      this.previewRows.set(rows);
    };
    reader.readAsText(file);
  }

  private setInvalidJsonState(
    translationKey = 'supervisor.customerImport.invalidJsonArrayError',
  ): void {
    this.fileError.set(this.transloco.translate(translationKey));
    this.selectedFile.set(null);
    this.previewHeaders.set([]);
    this.previewRows.set([]);
  }

  private stringifyPreviewCell(value: unknown): string {
    if (value === undefined || value === null) return '';
    if (typeof value === 'object') return JSON.stringify(value);
    return String(value);
  }

  private parseCSVLine(line: string, separator: string, quoteCharacter: string): string[] {
    const result: string[] = [];
    let current = '';
    let inQuotes = false;
    for (let i = 0; i < line.length; i++) {
      const ch = line[i];
      if (quoteCharacter && ch === quoteCharacter) {
        inQuotes = !inQuotes;
      } else if (!inQuotes && line.startsWith(separator, i)) {
        result.push(current.trim());
        current = '';
        i += separator.length - 1;
      } else {
        current += ch;
      }
    }
    result.push(current.trim());
    return result;
  }

  private buildInitialMappings(headers: string[]): void {
    const AUTO_MAP: Record<string, SystemField> = {
      phone: 'phone',
      telefon: 'phone',
      nr_tel: 'phone',
      mobile: 'phone',
      first_name: 'first_name',
      imie: 'first_name',
      firstname: 'first_name',
      last_name: 'last_name',
      nazwisko: 'last_name',
      lastname: 'last_name',
      external_id: 'external_id',
      externalid: 'external_id',
      crm_id: 'external_id',
      id_zewnetrzne: 'external_id',
      email: 'email',
      'e-mail': 'email',
      mail: 'email',
    };

    const mappings: ColumnMapping[] = headers.map((header) => {
      const key = header.toLowerCase().replace(/\s+/g, '_');
      return {
        csvHeader: header,
        systemField: AUTO_MAP[key] ?? 'skip',
      };
    });

    this.columnMappings.set(mappings);
  }

  formatFileSize(bytes: number): string {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  }

  goToMapping(): void {
    if (!this.selectedFile()) return;
    this.currentStep.set('mapping');
  }

  /** Primary action on the 'upload' step: CSV goes through mapping, JSON imports directly. */
  goToImportOrMapping(): void {
    if (!this.selectedFile()) return;
    if (this.importFormat() === 'json') {
      this.onImport();
    } else {
      this.goToMapping();
    }
  }

  // ── Step 2 handlers ──────────────────────────────────────────────────────

  updateMapping(index: number, value: SystemField): void {
    this.columnMappings.update((mappings) => {
      const next = [...mappings];
      next[index] = { ...next[index], systemField: value };
      return next;
    });
    this.mappingError.set(null);
  }

  updateCustomFieldKey(index: number, key: string): void {
    this.columnMappings.update((mappings) => {
      const next = [...mappings];
      next[index] = { ...next[index], customFieldKey: key };
      return next;
    });
    this.mappingError.set(null);
  }

  goBackToUpload(): void {
    this.currentStep.set('upload');
  }

  onImport(): void {
    if (this.importFormat() === 'json') {
      this.submitJsonImport();
      return;
    }

    if (!this.isPhoneMapped()) {
      this.mappingError.set('Pole "Telefon" musi byc zmapowane na jedna z kolumn CSV.');
      return;
    }

    const hasUnnamedCustomField = this.columnMappings().some(
      (m) => m.systemField === 'custom_fields' && !m.customFieldKey?.trim(),
    );
    if (hasUnnamedCustomField) {
      this.mappingError.set('Kazde pole dodatkowe musi miec nazwe.');
      return;
    }

    const customFieldKeys = this.columnMappings()
      .filter((m) => m.systemField === 'custom_fields')
      .map((m) => m.customFieldKey!.trim());
    const duplicateCustomFieldKey = customFieldKeys.find(
      (key, index) => customFieldKeys.indexOf(key) !== index,
    );
    if (duplicateCustomFieldKey) {
      this.mappingError.set(
        `Nazwa pola dodatkowego "${duplicateCustomFieldKey}" jest uzyta wiecej niz raz.`,
      );
      return;
    }

    const file = this.selectedFile();
    if (!file) return;

    this.mappingError.set(null);
    this.submitting.set(true);
    this.currentStep.set('progress');

    // Build mapping: systemField → column index (or indices for phone/email,
    // which support mapping multiple CSV columns onto the same system field;
    // named keys for custom_fields).
    const mappingObj: Record<string, unknown> = {};
    const phoneIndices: number[] = [];
    const emailIndices: number[] = [];
    const customFieldsObj: Record<string, number> = {};

    this.columnMappings().forEach((m, index) => {
      switch (m.systemField) {
        case 'phone':
          phoneIndices.push(index);
          break;
        case 'email':
          emailIndices.push(index);
          break;
        case 'custom_fields': {
          const key = m.customFieldKey?.trim();
          if (key) customFieldsObj[key] = index;
          break;
        }
        case 'skip':
          break;
        default:
          mappingObj[m.systemField] = index;
      }
    });

    if (phoneIndices.length > 0) mappingObj['phone'] = phoneIndices;
    if (emailIndices.length > 0) mappingObj['email'] = emailIndices;
    if (Object.keys(customFieldsObj).length > 0) mappingObj['custom_fields'] = customFieldsObj;

    this.customerService
      .importCsv(
        file,
        this.columnSeparator(),
        this.quoteChar(),
        this.deduplicationMode(),
        mappingObj,
      )
      .pipe(
        catchError(() => {
          this.notifications.error(this.transloco.translate('supervisor.customerImport.errorMsg'));
          this.submitting.set(false);
          this.currentStep.set('mapping');
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((response) => {
        this.submitting.set(false);
        if (response) {
          this.startPolling(response.jobId);
        }
      });
  }

  private submitJsonImport(): void {
    const file = this.selectedFile();
    if (!file) return;

    this.submitting.set(true);
    this.currentStep.set('progress');

    this.customerService
      .importJson(file, this.deduplicationMode())
      .pipe(
        catchError(() => {
          this.notifications.error(this.transloco.translate('supervisor.customerImport.errorMsg'));
          this.submitting.set(false);
          this.currentStep.set('upload');
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((response) => {
        this.submitting.set(false);
        if (response) {
          this.startPolling(response.jobId);
        }
      });
  }

  // ── Step 3 handlers ──────────────────────────────────────────────────────

  private startPolling(jobId: string): void {
    interval(POLLING_INTERVAL_MS)
      .pipe(
        switchMap(() =>
          this.customerService.getImportStatus(jobId).pipe(catchError(() => of(null))),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((status) => {
        if (!status) return;
        this.jobStatus.set(status);
        if (
          status.status === 'COMPLETED' ||
          status.status === 'FAILED_PARTIAL' ||
          status.status === 'FAILED'
        ) {
          this.currentStep.set('report');
        }
      });
  }

  // ── Step 4 handlers ──────────────────────────────────────────────────────

  downloadErrors(): void {
    const job = this.jobStatus();
    if (!job) return;

    this.customerService
      .downloadImportErrors(job.jobId)
      .pipe(
        catchError(() => {
          this.notifications.error(this.transloco.translate('supervisor.customerImport.errorMsg'));
          return of(null);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((blob) => {
        if (!blob) return;
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `import-errors-${job.jobId}.csv`;
        a.click();
        URL.revokeObjectURL(url);
      });
  }

  navigateToList(): void {
    void this.router.navigate(['/supervisor/customers']);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  getStepNumber(step: ImportStep): number {
    return this.allSteps().indexOf(step) + 1;
  }

  isStepCompleted(step: ImportStep): boolean {
    return this.allSteps().indexOf(step) < this.allSteps().indexOf(this.currentStep());
  }

  isStepActive(step: ImportStep): boolean {
    return this.currentStep() === step;
  }
}
