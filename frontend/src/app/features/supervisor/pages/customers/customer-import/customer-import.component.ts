import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
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
  | 'email'
  | 'custom_fields'
  | 'skip';

export interface ColumnMapping {
  csvHeader: string;
  systemField: SystemField;
}

const MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024; // 50 MB
const POLLING_INTERVAL_MS = 3_000;
const PREVIEW_ROWS = 5;

@Component({
  selector: 'app-customer-import',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  templateUrl: './customer-import.component.html',
  styleUrl: './customer-import.component.scss',
})
export class CustomerImportComponent implements OnInit {
  private readonly customerService = inject(CustomerService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  // ── Step state ────────────────────────────────────────────────────────────
  readonly currentStep = signal<ImportStep>('upload');
  readonly allSteps: ImportStep[] = ['upload', 'mapping', 'progress', 'report'];

  // ── Step 1: Upload ────────────────────────────────────────────────────────
  readonly selectedFile = signal<File | null>(null);
  readonly deduplicationMode = signal<DeduplicationMode>('SKIP');
  readonly columnSeparator = signal<string>(',');
  readonly quoteChar = signal<string>('"');
  readonly fileError = signal<string | null>(null);
  readonly isDragging = signal(false);
  readonly csvPreviewRows = signal<string[][]>([]);
  readonly csvHeaders = signal<string[]>([]);

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
    { value: 'email', label: 'Email' },
    { value: 'custom_fields', label: 'Pole dodatkowe' },
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
      this.columnSeparator();
      this.quoteChar();
      const file = this.selectedFile();
      if (file) {
        this.parseCsvPreview(file);
      }
    });
  }

  ngOnInit(): void {
    // nothing to init – state is signal-driven
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

    if (!file.name.toLowerCase().endsWith('.csv')) {
      this.fileError.set('Dozwolone sa tylko pliki CSV (.csv).');
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

    this.selectedFile.set(file);
    this.parseCsvPreview(file);
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
        this.csvHeaders.set([]);
        this.csvPreviewRows.set([]);
        return;
      }

      const allRows = lines.map((l) => this.parseCSVLine(l, sep, quote));
      const firstRow = allRows[0];

      const looksLikeHeader = firstRow.every(
        (cell) => isNaN(Number(cell)) && cell.trim() !== '',
      );
      let headers: string[];
      let dataRows: string[][];

      if (looksLikeHeader) {
        headers = firstRow;
        dataRows = allRows.slice(1, PREVIEW_ROWS + 1);
      } else {
        headers = firstRow.map((_, i) => `Kolumna ${i + 1}`);
        dataRows = allRows.slice(0, PREVIEW_ROWS);
      }

      this.csvHeaders.set(headers);
      this.csvPreviewRows.set(dataRows);
      this.buildInitialMappings(headers);
    };
    reader.readAsText(file);
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

  // ── Step 2 handlers ──────────────────────────────────────────────────────

  updateMapping(index: number, value: SystemField): void {
    this.columnMappings.update((mappings) => {
      const next = [...mappings];
      next[index] = { ...next[index], systemField: value };
      return next;
    });
    this.mappingError.set(null);
  }

  goBackToUpload(): void {
    this.currentStep.set('upload');
  }

  onImport(): void {
    if (!this.isPhoneMapped()) {
      this.mappingError.set('Pole "Telefon" musi byc zmapowane na jedna z kolumn CSV.');
      return;
    }

    const file = this.selectedFile();
    if (!file) return;

    this.mappingError.set(null);
    this.submitting.set(true);
    this.currentStep.set('progress');

    // Build mapping: systemField → column index
    const mappingObj: Record<string, number> = {};
    this.columnMappings().forEach((m, index) => {
      if (m.systemField && m.systemField !== 'skip') {
        mappingObj[m.systemField] = index;
      }
    });

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
          this.notifications.error('Nie udalo sie rozpoczac importu. Sprobuj ponownie.');
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
          this.notifications.error('Nie udalo sie pobrac pliku bledow.');
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
    return this.allSteps.indexOf(step) + 1;
  }

  isStepCompleted(step: ImportStep): boolean {
    return this.allSteps.indexOf(step) < this.allSteps.indexOf(this.currentStep());
  }

  isStepActive(step: ImportStep): boolean {
    return this.currentStep() === step;
  }
}
