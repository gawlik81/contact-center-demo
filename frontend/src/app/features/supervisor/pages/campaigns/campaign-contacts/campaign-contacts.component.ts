import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, of } from 'rxjs';
import { CampaignService } from '../../../services/campaign.service';
import {
  Campaign,
  CampaignContact,
  CampaignContactStatus,
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
  imports: [],
  templateUrl: './campaign-contacts.component.html',
  styleUrl: './campaign-contacts.component.scss',
  host: {
    '(document:keydown.escape)': 'onEscapeKey($event)',
  },
})
export class CampaignContactsComponent implements AfterViewInit {
  readonly campaign = input.required<Campaign>();
  readonly closed = output<void>();

  private readonly campaignService = inject(CampaignService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly dialogRef = viewChild<ElementRef<HTMLDialogElement>>('dialogEl');

  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly contacts = signal<CampaignContact[]>([]);
  readonly totalElements = signal(0);
  readonly totalPages = signal(0);
  readonly currentPage = signal(0);
  readonly selectedStatus = signal<string>('');

  readonly statusOptions: StatusOption[] = [
    { value: '', label: 'Wszystkie' },
    { value: 'PENDING', label: 'Oczekujace' },
    { value: 'CALLED', label: 'Zadzwonione' },
    { value: 'FAILED', label: 'Blad' },
    { value: 'SKIPPED', label: 'Pominiete' },
  ];

  ngAfterViewInit(): void {
    const dialog = this.dialogRef()?.nativeElement;
    if (dialog && !dialog.open) {
      dialog.showModal();
    }
    this.loadContacts();
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
          this.error.set('Nie udalo sie pobrac listy kontaktow. Sprobuj ponownie.');
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
    switch (status) {
      case 'PENDING':
        return 'Oczekujacy';
      case 'CALLED':
        return 'Zadzwoniony';
      case 'FAILED':
        return 'Blad';
      case 'SKIPPED':
        return 'Pominieto';
      default:
        return status;
    }
  }

  readonly firstItemIndex = (): number => this.currentPage() * PAGE_SIZE + 1;
  readonly lastItemIndex = (): number =>
    Math.min((this.currentPage() + 1) * PAGE_SIZE, this.totalElements());

  trackByRecordId(_index: number, contact: CampaignContact): string {
    return contact.recordId;
  }
}
