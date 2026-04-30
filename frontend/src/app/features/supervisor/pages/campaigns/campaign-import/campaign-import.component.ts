import { TranslocoModule } from '@jsverse/transloco';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { interval, switchMap, catchError, of } from 'rxjs';
import { CampaignService } from '../../../services/campaign.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { Campaign, ImportJobStatus } from '../../../models/campaign.model';

export type ImportStep = 'upload' | 'mapping' | 'progress' | 'report';

export type SystemField =
  | 'phone'
  | 'first_name'
  | 'last_name'
  | 'custom_field_1'
  | 'custom_field_2'
  | 'skip';

export interface ColumnMapping {
  csvHeader: string;
  systemField: SystemField;
}

const MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024; // 50 MB
const POLLING_INTERVAL_MS = 3_000;
const PREVIEW_ROWS = 5;

@Component({
  selector: 'app-campaign-import',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoModule,FormsModule],
  templateUrl: './campaign-import.component.html',
  styleUrl: './campaign-import.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class CampaignImportComponent implements AfterViewInit {
  readonly campaign = input.required<Campaign>();

  /** Emits true when import completed successfully (at least partially), false on cancel */
  readonly closed = output<boolean>();

  private readonly campaignService = inject(CampaignService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  // ── Step state ────────────────────────────────────────────────────────────
  readonly currentStep = signal<ImportStep>('upload');

  // ── Step 1: Upload ────────────────────────────────────────────────────────
  readonly selectedFile = signal<File | null>(null);
  readonly skipDuplicates = signal(true);
  readonly columnSeparator = signal<string>(',');
  readonly quoteChar = signal<string>('"');
  readonly fileError = signal<string | null>(null);
  readonly isDragging = signal(false);
  readonly csvPreviewRows = signal<string[][]>([]);
  readonly csvHeaders = signal<string[]>([]);

  readonly columnSeparatorOptions: { value: string; label: string }[] = [
    { value: ',', label: ', (przecinek)' },
    { value: ';', label: '; (srednik)' },
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

  readonly allSteps: ImportStep[] = ['upload', 'mapping', 'progress', 'report'];

  readonly systemFieldOptions: { value: SystemField; label: string }[] = [
    { value: 'phone', label: 'Telefon (wymagany)' },
    { value: 'first_name', label: 'Imie' },
    { value: 'last_name', label: 'Nazwisko' },
    { value: 'custom_field_1', label: 'Pole niestandardowe 1' },
    { value: 'custom_field_2', label: 'Pole niestandardowe 2' },
    { value: 'skip', label: '(Pomin)' },
  ];

  readonly isPhoneMapped = computed(() =>
    this.columnMappings().some((m) => m.systemField === 'phone'),
  );

  // ── Step 3: Progress ──────────────────────────────────────────────────────
  readonly jobStatus = signal<ImportJobStatus | null>(null);
  readonly progressPercent = computed(() => {
    const job = this.jobStatus();
    if (!job || job.totalRows === 0) return 0;
    return Math.round((job.processedRows / job.totalRows) * 100);
  });
  readonly submitting = signal(false);

  // ── Constructor ──────────────────────────────────────────────────────────
  constructor() {
    // Re-parse CSV preview whenever separator or quote char changes
    effect(() => {
      // Read all three signals to register as dependencies
      this.columnSeparator();
      this.quoteChar();
      const file = this.selectedFile();
      if (file) {
        this.parseCsvPreview(file);
      }
    });
  }

  // ── Lifecycle ────────────────────────────────────────────────────────────
  ngAfterViewInit(): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
  }

  onEscapeKey(event: Event): void {
    event.preventDefault();
    this.onClose(false);
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

      // Detect header: if all cells are non-numeric strings → treat as header row
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
      first_name: 'first_name',
      imie: 'first_name',
      last_name: 'last_name',
      nazwisko: 'last_name',
      custom_field_1: 'custom_field_1',
      custom_field_2: 'custom_field_2',
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

    // Zbuduj mapowanie: systemField → indeks kolumny CSV
    const mappingObj: Record<string, number> = {};
    this.columnMappings().forEach((m, index) => {
      if (m.systemField && m.systemField !== 'skip') {
        mappingObj[m.systemField] = index;
      }
    });

    this.campaignService
      .importContacts(
        this.campaign().campaignId,
        file,
        this.skipDuplicates(),
        this.columnSeparator(),
        this.quoteChar(),
        JSON.stringify(mappingObj),
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
          this.campaignService
            .getImportStatus(this.campaign().campaignId, jobId)
            .pipe(catchError(() => of(null))),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((status) => {
        if (!status) return;
        this.jobStatus.set(status);
        if (status.status === 'COMPLETED' || status.status === 'FAILED_PARTIAL') {
          this.currentStep.set('report');
        }
      });
  }

  // ── Step 4 / Close ────────────────────────────────────────────────────────

  onClose(success: boolean): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog?.open) {
      dialog.close();
    }
    this.closed.emit(success);
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  getStepNumber(step: ImportStep): number {
    const steps: ImportStep[] = ['upload', 'mapping', 'progress', 'report'];
    return steps.indexOf(step) + 1;
  }

  isStepCompleted(step: ImportStep): boolean {
    const order: ImportStep[] = ['upload', 'mapping', 'progress', 'report'];
    return order.indexOf(step) < order.indexOf(this.currentStep());
  }

  isStepActive(step: ImportStep): boolean {
    return this.currentStep() === step;
  }
}
