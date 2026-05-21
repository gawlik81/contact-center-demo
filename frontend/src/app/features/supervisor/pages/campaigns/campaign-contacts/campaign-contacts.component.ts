import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { DatePipe, DecimalPipe, LowerCasePipe } from '@angular/common';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  OnInit,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, finalize, of } from 'rxjs';
import { CampaignService } from '../../../services/campaign.service';
import { DialerService } from '../../../../../features/agent/services/dialer.service';
import { AuthService } from '../../../../../core/services/auth.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import {
  Campaign,
  CampaignContact,
  CampaignContactStatus,
  ContactAttempt,
  PagedResponse,
} from '../../../models/campaign.model';

const PAGE_SIZE = 50;

interface StatusOption {
  value: string;
  label: string;
}

@Component({
  selector: 'app-campaign-contacts',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule, DatePipe, DecimalPipe, LowerCasePipe],
  templateUrl: './campaign-contacts.component.html',
  styleUrl: './campaign-contacts.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class CampaignContactsComponent implements OnInit, AfterViewInit {
  readonly campaign = input.required<Campaign>();
  readonly closed = output<void>();
  readonly contactSelected = output<string>();

  private readonly campaignService = inject(CampaignService);
  private readonly dialerService = inject(DialerService);
  private readonly authService = inject(AuthService);
  private readonly notificationService = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly translocoService = inject(TranslocoService);

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly contacts = signal<CampaignContact[]>([]);
  readonly totalElements = signal(0);
  readonly totalPages = signal(0);
  readonly currentPage = signal(0);
  readonly selectedStatus = signal<string>('');
  readonly callingRecordId = signal<string | null>(null);
  readonly expandedRecordId = signal<string | null>(null);
  readonly attempts = signal<ContactAttempt[]>([]);
  readonly attemptsLoading = signal(false);

  readonly isAgent = (): boolean => this.authService.currentRole() === 'AGENT';
  readonly isManualCampaign = (): boolean => this.campaign().dialerType === 'MANUAL';

  readonly statusOptions = signal<StatusOption[]>([]);

  ngOnInit(): void {
    this.initStatusOptions();
    this.translocoService.langChanges$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.initStatusOptions();
    });
  }

  ngAfterViewInit(): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
    this.loadContacts();
  }

  private initStatusOptions(): void {
    const t = (key: string) => this.translocoService.translate(key);
    this.statusOptions.set([
      { value: '', label: t('common.all') },
      { value: 'PENDING', label: t('supervisor.campaignContacts.contactStatusLabels.PENDING') },
      { value: 'DIALING', label: t('supervisor.campaignContacts.contactStatusLabels.DIALING') },
      {
        value: 'CONNECTED',
        label: t('supervisor.campaignContacts.contactStatusLabels.CONNECTED'),
      },
      {
        value: 'COMPLETED',
        label: t('supervisor.campaignContacts.contactStatusLabels.COMPLETED'),
      },
      {
        value: 'NO_ANSWER',
        label: t('supervisor.campaignContacts.contactStatusLabels.NO_ANSWER'),
      },
      {
        value: 'NOT_REACHED',
        label: t('supervisor.campaignContacts.contactStatusLabels.NOT_REACHED'),
      },
      {
        value: 'CALLBACK',
        label: t('supervisor.campaignContacts.contactStatusLabels.CALLBACK'),
      },
      { value: 'FAILED', label: t('supervisor.campaignContacts.contactStatusLabels.FAILED') },
      { value: 'SKIPPED', label: t('supervisor.campaignContacts.contactStatusLabels.SKIPPED') },
      { value: 'ERROR', label: t('supervisor.campaignContacts.contactStatusLabels.ERROR') },
    ]);
  }

  onEscapeKey(event: Event): void {
    event.preventDefault();
    this.close();
  }

  onStatusChange(event: Event): void {
    const el = event.target as HTMLSelectElement;
    this.selectedStatus.set(el.value);
    this.currentPage.set(0);
    this.loadContacts();
  }

  loadContacts(): void {
    this.loading.set(true);
    this.error.set(null);
    const status = this.selectedStatus() || undefined;
    this.campaignService
      .getCampaignContacts(this.campaign().campaignId, this.currentPage(), PAGE_SIZE, status)
      .pipe(
        catchError(() => {
          this.error.set(
            this.translocoService.translate('supervisor.campaignContacts.errors.loadFailed'),
          );
          const empty: PagedResponse<CampaignContact> = {
            content: [],
            page: 0,
            size: PAGE_SIZE,
            totalElements: 0,
            totalPages: 0,
            first: true,
            last: true,
          };
          return of(empty);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((response) => {
        this.contacts.set(response.content);
        this.totalElements.set(response.totalElements);
        this.totalPages.set(response.totalPages);
        this.loading.set(false);
      });
  }

  onPrevPage(): void {
    if (this.currentPage() > 0) {
      this.currentPage.update((p) => p - 1);
      this.loadContacts();
    }
  }

  onNextPage(): void {
    if (this.currentPage() + 1 < this.totalPages()) {
      this.currentPage.update((p) => p + 1);
      this.loadContacts();
    }
  }

  close(): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog?.open) {
      dialog.close();
    }
    this.closed.emit();
  }

  formatDate(dateStr: string): string {
    try {
      return new Date(dateStr).toLocaleString('pl-PL', {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch {
      return dateStr;
    }
  }

  statusLabel(status: CampaignContactStatus): string {
    return this.translocoService.translate(
      `supervisor.campaignContacts.contactStatusLabels.${status}`,
    );
  }

  formatRelativeTime(dateStr: string | null): string {
    if (!dateStr) return '—';
    try {
      const date = new Date(dateStr);
      const now = new Date();
      const diffMs = date.getTime() - now.getTime();
      const diffMin = Math.round(diffMs / 60000);

      if (diffMin < 0) return this.translocoService.translate('supervisor.campaignContacts.now');
      if (diffMin < 60) return `za ${diffMin} min`;

      const diffHours = Math.floor(diffMin / 60);
      if (diffHours < 24) return `za ${diffHours} h`;

      return date.toLocaleString('pl-PL', {
        day: '2-digit',
        month: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch {
      return dateStr;
    }
  }

  get maxAttempts(): number {
    return this.campaign().maxAttempts;
  }

  showAttempts(contact: CampaignContact): void {
    if (this.expandedRecordId() === contact.recordId) {
      this.expandedRecordId.set(null);
      return;
    }
    this.expandedRecordId.set(contact.recordId);
    this.attemptsLoading.set(true);
    this.campaignService
      .getContactAttempts(this.campaign().campaignId, contact.recordId)
      .pipe(
        catchError(() => of([])),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.attemptsLoading.set(false)),
      )
      .subscribe((a) => this.attempts.set(a));
  }

  openContactDetail(contactId: string): void {
    this.contactSelected.emit(contactId);
  }

  callRecord(contact: CampaignContact): void {
    if (this.callingRecordId() !== null) return;

    this.callingRecordId.set(contact.recordId);
    this.dialerService
      .manualCall(this.campaign().campaignId, contact.recordId)
      .pipe(
        finalize(() => this.callingRecordId.set(null)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: () => {
          this.contacts.update((list) =>
            list.map((c) =>
              c.recordId === contact.recordId
                ? { ...c, status: 'DIALING' as CampaignContactStatus }
                : c,
            ),
          );
        },
        error: (err) => {
          if (err.status === 409) {
            const msg: string =
              err.error?.message ??
              this.translocoService.translate('supervisor.campaignContacts.errors.callFailed');
            this.notificationService.error(msg);
          } else if (err.status === 404) {
            this.notificationService.error(
              this.translocoService.translate('supervisor.campaignContacts.errors.callNotFound'),
            );
          } else {
            this.notificationService.error(
              this.translocoService.translate('supervisor.campaignContacts.errors.callFailed'),
            );
          }
        },
      });
  }

  readonly firstItemIndex = (): number => this.currentPage() * PAGE_SIZE + 1;
  readonly lastItemIndex = (): number =>
    Math.min((this.currentPage() + 1) * PAGE_SIZE, this.totalElements());

  trackByRecordId(_index: number, contact: CampaignContact): string {
    return contact.recordId;
  }
}
