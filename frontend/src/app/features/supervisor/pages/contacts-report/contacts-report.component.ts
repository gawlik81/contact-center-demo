import { TranslocoModule } from '@jsverse/transloco';
import {ChangeDetectionStrategy, Component, DestroyRef, ElementRef, inject, OnInit, signal, ViewChild,} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {FormBuilder, ReactiveFormsModule} from '@angular/forms';
import {ActivatedRoute, Router} from '@angular/router';
import {catchError, debounceTime, distinctUntilChanged, finalize, of} from 'rxjs';
import {ContactFilterParams, ContactResponse, ContactService} from '../../../agent/services/contact.service';
import {QueueService} from '../../services/queue.service';
import {NotificationService} from '../../../../core/services/notification.service';
import {Queue} from '../../models/queue.model';
import {PagedResponse} from '../../../../core/models/paged-response.model';
import {ContactDetailModalComponent} from '../../../../shared/components/contact-detail-modal/contact-detail-modal.component';

@Component({
  selector: 'app-contacts-report',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoModule,ReactiveFormsModule, ContactDetailModalComponent],
  templateUrl: './contacts-report.component.html',
  styleUrl: './contacts-report.component.scss',
})
export class ContactsReportComponent implements OnInit {
  @ViewChild('tableTop') tableTopRef!: ElementRef<HTMLElement>;

  private readonly contactService = inject(ContactService);
  private readonly queueService = inject(QueueService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(false);
  readonly exportingCsv = signal(false);
  readonly rows = signal<ContactResponse[]>([]);
  readonly totalElements = signal(0);
  readonly totalPages = signal(0);
  readonly currentPage = signal(0);
  readonly pageSize = 25;
  readonly queues = signal<Queue[]>([]);

  readonly selectedContactId = signal<string | null>(null);

  readonly channels: { value: string; label: string }[] = [
    { value: 'PHONE', label: 'Telefon' },
    { value: 'EMAIL', label: 'Email' },
    { value: 'CHAT', label: 'Chat' },
    { value: 'SOCIAL', label: 'Social Media' },
  ];
  readonly statuses = ['COMPLETED', 'ABANDONED', 'FAILED', 'ACTIVE', 'QUEUED', 'ON_HOLD'];

  private formatDate(d: Date): string {
    const yyyy = d.getFullYear();
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${yyyy}-${mm}-${dd}`;
  }

  private get defaultDateFrom(): string {
    const d = new Date();
    d.setDate(d.getDate() - 7);
    return this.formatDate(d);
  }

  private get defaultDateTo(): string {
    return this.formatDate(new Date());
  }

  readonly filterForm = this.fb.group({
    dateFrom: [this.defaultDateFrom],
    dateTo: [this.defaultDateTo],
    channel: [''],
    status: [''],
    queueId: [''],
    remoteAddress: [''],
    durationMin: [null as number | null],
    durationMax: [null as number | null],
  });

  ngOnInit(): void {
    this.loadQueues();
    this.restoreFiltersFromUrl();
    this.loadData();

    // Debounce for text/number fields
    this.filterForm
      .get('remoteAddress')!
      .valueChanges.pipe(
      debounceTime(400),
      distinctUntilChanged(),
      takeUntilDestroyed(this.destroyRef),
    )
      .subscribe(() => {
        this.currentPage.set(0);
        this.loadData();
      });

    this.filterForm
      .get('durationMin')!
      .valueChanges.pipe(
      debounceTime(400),
      distinctUntilChanged(),
      takeUntilDestroyed(this.destroyRef),
    )
      .subscribe(() => {
        this.currentPage.set(0);
        this.loadData();
      });

    this.filterForm
      .get('durationMax')!
      .valueChanges.pipe(
      debounceTime(400),
      distinctUntilChanged(),
      takeUntilDestroyed(this.destroyRef),
    )
      .subscribe(() => {
        this.currentPage.set(0);
        this.loadData();
      });
  }

  private loadQueues(): void {
    this.queueService
      .getQueues(0, 100)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (res) => this.queues.set(res.content),
        error: () => {
          // non-critical
        },
      });
  }

  private restoreFiltersFromUrl(): void {
    const params = this.route.snapshot.queryParams;
    if (params['dateFrom']) {
      this.filterForm.patchValue({
        dateFrom: params['dateFrom'],
        dateTo: params['dateTo'] ?? this.defaultDateTo,
        channel: params['channel'] ?? '',
        status: params['status'] ?? '',
        queueId: params['queueId'] ?? '',
        remoteAddress: params['remoteAddress'] ?? '',
        durationMin: params['durationMin'] ? Number(params['durationMin']) : null,
        durationMax: params['durationMax'] ? Number(params['durationMax']) : null,
      });
    }
    if (params['page']) {
      const page = parseInt(params['page'], 10);
      if (!isNaN(page) && page >= 0) {
        this.currentPage.set(page);
      }
    }
  }

  private buildFilters(): ContactFilterParams {
    const v = this.filterForm.getRawValue();
    const filters: ContactFilterParams = {};
    if (v.dateFrom) filters.dateFrom = v.dateFrom;
    if (v.dateTo) filters.dateTo = v.dateTo;
    if (v.channel) filters.channel = v.channel;
    if (v.status) filters.status = v.status;
    if (v.queueId) filters.queueId = v.queueId;
    if (v.remoteAddress) filters.remoteAddress = v.remoteAddress;
    if (v.durationMin !== null && v.durationMin !== undefined) filters.durationMin = v.durationMin;
    if (v.durationMax !== null && v.durationMax !== undefined) filters.durationMax = v.durationMax;
    return filters;
  }

  private syncUrlParams(): void {
    const v = this.filterForm.getRawValue();
    const queryParams: Record<string, string | number | null> = {
      dateFrom: v.dateFrom ?? null,
      dateTo: v.dateTo ?? null,
      page: this.currentPage() > 0 ? this.currentPage() : null,
    };
    if (v.channel) queryParams['channel'] = v.channel;
    if (v.status) queryParams['status'] = v.status;
    if (v.queueId) queryParams['queueId'] = v.queueId;
    if (v.remoteAddress) queryParams['remoteAddress'] = v.remoteAddress;
    if (v.durationMin !== null && v.durationMin !== undefined) queryParams['durationMin'] = v.durationMin;
    if (v.durationMax !== null && v.durationMax !== undefined) queryParams['durationMax'] = v.durationMax;

    this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      replaceUrl: true,
    });
  }

  loadData(): void {
    this.loading.set(true);
    this.syncUrlParams();

    this.contactService
      .getContacts(this.buildFilters(), this.currentPage(), this.pageSize)
      .pipe(
        catchError(() => {
          this.notifications.error('Nie udalo sie pobrac listy kontaktow. Sprobuj ponownie.');
          return of<PagedResponse<ContactResponse>>({
            content: [],
            totalElements: 0,
            totalPages: 0,
            page: 0,
            size: this.pageSize,
            first: true,
            last: true,
          });
        }),
        finalize(() => this.loading.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((response) => {
        this.rows.set(response.content);
        this.totalElements.set(response.totalElements);
        this.totalPages.set(response.totalPages);
      });
  }

  onSearch(): void {
    this.currentPage.set(0);
    this.loadData();
  }

  onClearFilters(): void {
    this.filterForm.reset({
      dateFrom: this.defaultDateFrom,
      dateTo: this.defaultDateTo,
      channel: '',
      status: '',
      queueId: '',
      remoteAddress: '',
      durationMin: null,
      durationMax: null,
    });
    this.currentPage.set(0);
    this.loadData();
  }

  onPrevPage(): void {
    if (this.currentPage() > 0) {
      this.currentPage.update((p) => p - 1);
      this.scrollToTable();
      this.loadData();
    }
  }

  onNextPage(): void {
    if (this.currentPage() + 1 < this.totalPages()) {
      this.currentPage.update((p) => p + 1);
      this.scrollToTable();
      this.loadData();
    }
  }

  private scrollToTable(): void {
    if (this.tableTopRef?.nativeElement) {
      this.tableTopRef.nativeElement.scrollIntoView({behavior: 'smooth', block: 'start'});
    }
  }

  onExportCsv(): void {
    this.exportingCsv.set(true);
    const data = this.rows();
    try {
      const headers = [
        'Data i czas',
        'Kanal',
        'Kierunek',
        'Numer/adres',
        'Kolejka',
        'Czas trwania (s)',
        'Status',
        'Dyspozycja',
      ];
      const csvRows = data.map((c) => [
        this.formatDateTime(c.startedAt),
        c.channel,
        c.direction,
        c.remoteAddress ?? '',
        c.queueId ?? '',
        c.durationSeconds !== undefined ? String(c.durationSeconds) : '',
        c.status,
        c.dispositionCode ?? '',
      ]);
      const csvContent = [headers, ...csvRows]
        .map((row) => row.map((cell) => `"${String(cell).replace(/"/g, '""')}"`).join(','))
        .join('\n');
      const blob = new Blob(['\uFEFF' + csvContent], {type: 'text/csv;charset=utf-8;'});
      const today = this.formatDate(new Date());
      this.downloadFile(blob, `contacts-${today}.csv`);
    } finally {
      this.exportingCsv.set(false);
    }
  }

  private downloadFile(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  }

  // ─── Helpers ────────────────────────────────────────────────────────────────

  formatDateTime(iso: string): string {
    if (!iso) return '—';
    const d = new Date(iso);
    const dd = String(d.getDate()).padStart(2, '0');
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const yyyy = d.getFullYear();
    const hh = String(d.getHours()).padStart(2, '0');
    const min = String(d.getMinutes()).padStart(2, '0');
    return `${dd}.${mm}.${yyyy} ${hh}:${min}`;
  }

  formatDuration(seconds: number | undefined): string {
    if (seconds === undefined || seconds === null) return '—';
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }

  getQueueName(queueId: string | undefined): string {
    if (!queueId) return '—';
    const q = this.queues().find((queue) => queue.queueId === queueId);
    return q ? q.name : queueId.substring(0, 8) + '...';
  }

  getChannelLabel(channel: string): string {
    return this.channels.find(c => c.value === channel)?.label ?? channel;
  }

  getDirectionLabel(direction: string): string {
    return direction === 'INBOUND' ? 'Przych.' : 'Wych.';
  }

  trackByContact(_index: number, row: ContactResponse): string {
    return row.contactId;
  }

  readonly skeletonRows = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
}
