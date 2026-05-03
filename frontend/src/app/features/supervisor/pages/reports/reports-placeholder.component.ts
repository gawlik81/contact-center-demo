import { TranslocoModule } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  ViewChild,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { catchError, finalize, of } from 'rxjs';
import { ReportsService } from '../../services/reports.service';
import { UserService } from '../../services/user.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { AgentReportFilters, AgentReportRow } from '../../models/report.model';
import { UserResponse } from '../../models/user.model';
import { PagedResponse } from '../../models/user.model';

@Component({
  selector: 'app-reports',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, ReactiveFormsModule],
  templateUrl: './reports-placeholder.component.html',
  styleUrl: './reports-placeholder.component.scss',
})
export class ReportsComponent implements OnInit {
  @ViewChild('tableTop') tableTopRef!: ElementRef<HTMLElement>;

  private readonly reportsService = inject(ReportsService);
  private readonly userService = inject(UserService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(false);
  readonly exportingCsv = signal(false);
  readonly exportingXlsx = signal(false);
  readonly rows = signal<AgentReportRow[]>([]);
  readonly totalElements = signal(0);
  readonly totalPages = signal(0);
  readonly currentPage = signal(0);
  readonly pageSize = 20;
  readonly agents = signal<UserResponse[]>([]);

  readonly channels = ['CALL', 'EMAIL', 'CHAT', 'SOCIAL'];

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

  readonly filterForm = this.fb.group(
    {
      dateFrom: [this.defaultDateFrom, Validators.required],
      dateTo: [this.defaultDateTo, Validators.required],
      agentId: [''],
      channel: [''],
    },
    { validators: this.dateRangeValidator.bind(this) },
  );

  get dateRangeError(): string | null {
    const form = this.filterForm;
    if (form.hasError('dateTooEarly')) {
      return 'Data "Do" nie moze byc wczesniejsza niz data "Od".';
    }
    if (form.hasError('rangeTooLong')) {
      return 'Maksymalny zakres dat to 90 dni.';
    }
    return null;
  }

  get filtersAreValid(): boolean {
    return this.filterForm.valid;
  }

  ngOnInit(): void {
    this.loadAgents();
    this.restoreFiltersFromUrl();
    this.loadData();
  }

  private dateRangeValidator(
    group: ReturnType<FormBuilder['group']>,
  ): Record<string, boolean> | null {
    const from = group.get('dateFrom')?.value as string;
    const to = group.get('dateTo')?.value as string;
    if (!from || !to) return null;
    const fromDate = new Date(from);
    const toDate = new Date(to);
    if (toDate < fromDate) {
      return { dateTooEarly: true };
    }
    const diffDays = (toDate.getTime() - fromDate.getTime()) / (1000 * 60 * 60 * 24);
    if (diffDays > 90) {
      return { rangeTooLong: true };
    }
    return null;
  }

  private loadAgents(): void {
    this.userService
      .getUsers({ page: 0, size: 200, role: 'AGENT' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (response) => this.agents.set(response.content),
        error: () => {
          // non-critical – just leave the dropdown empty
        },
      });
  }

  private restoreFiltersFromUrl(): void {
    const params = this.route.snapshot.queryParams;
    if (params['dateFrom']) {
      this.filterForm.patchValue({
        dateFrom: params['dateFrom'],
        dateTo: params['dateTo'] ?? this.defaultDateTo,
        agentId: params['agentId'] ?? '',
        channel: params['channel'] ?? '',
      });
    }
    if (params['page']) {
      const page = parseInt(params['page'], 10);
      if (!isNaN(page) && page >= 0) {
        this.currentPage.set(page);
      }
    }
  }

  private buildFilters(): AgentReportFilters {
    const v = this.filterForm.getRawValue();
    const filters: AgentReportFilters = {
      dateFrom: v.dateFrom ?? this.defaultDateFrom,
      dateTo: v.dateTo ?? this.defaultDateTo,
    };
    if (v.agentId) filters.agentId = v.agentId;
    if (v.channel) filters.channel = v.channel;
    return filters;
  }

  private syncUrlParams(): void {
    const v = this.filterForm.getRawValue();
    const queryParams: Record<string, string | number | null> = {
      dateFrom: v.dateFrom ?? null,
      dateTo: v.dateTo ?? null,
      page: this.currentPage() > 0 ? this.currentPage() : null,
    };
    if (v.agentId) queryParams['agentId'] = v.agentId;
    if (v.channel) queryParams['channel'] = v.channel;

    this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      replaceUrl: true,
    });
  }

  loadData(): void {
    if (!this.filterForm.valid) return;

    this.loading.set(true);
    this.syncUrlParams();

    this.reportsService
      .getAgentReport(this.buildFilters(), this.currentPage(), this.pageSize)
      .pipe(
        catchError(() => {
          this.notifications.error('Nie udalo sie pobrac raportu. Sprobuj ponownie.');
          return of<PagedResponse<AgentReportRow>>({
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
      agentId: '',
      channel: '',
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
      this.tableTopRef.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }

  onExportCsv(): void {
    if (!this.filterForm.valid) return;
    this.exportingCsv.set(true);
    this.reportsService
      .exportCsv(this.buildFilters())
      .pipe(
        catchError(() => {
          this.notifications.error('Nie udalo sie wyeksportowac CSV. Sprobuj ponownie.');
          return of(null);
        }),
        finalize(() => this.exportingCsv.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((blob) => {
        if (blob) this.downloadFile(blob, 'raport-agentow.csv');
      });
  }

  onExportXlsx(): void {
    if (!this.filterForm.valid) return;
    this.exportingXlsx.set(true);
    this.reportsService
      .exportXlsx(this.buildFilters())
      .pipe(
        catchError(() => {
          this.notifications.error('Nie udalo sie wyeksportowac XLSX. Sprobuj ponownie.');
          return of(null);
        }),
        finalize(() => this.exportingXlsx.set(false)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((blob) => {
        if (blob) this.downloadFile(blob, 'raport-agentow.xlsx');
      });
  }

  private downloadFile(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.click();
    URL.revokeObjectURL(url);
  }

  secondsToTime(seconds: number): string {
    const total = Math.round(seconds);
    const m = Math.floor(total / 60);
    const s = total % 60;
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }

  fcrPercent(value: number): string {
    return `${Math.round(value * 100)}%`;
  }

  trackByRow(_index: number, row: AgentReportRow): string {
    return `${row.agentId}-${row.date}-${row.channel}`;
  }
}
