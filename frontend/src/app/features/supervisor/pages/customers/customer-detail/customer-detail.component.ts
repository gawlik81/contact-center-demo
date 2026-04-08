import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe, KeyValuePipe } from '@angular/common';
import { ActivatedRoute, Router } from '@angular/router';
import { catchError, finalize, of, switchMap } from 'rxjs';
import { CustomerService } from '../services/customer.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { CustomerResponse } from '../../../models/customer.model';
import { ContactResponse } from '../../../../../core/models/contact.model';
import { ContactDetailModalComponent } from '../../../../../shared/components/contact-detail-modal/contact-detail-modal.component';

type LoadState = 'loading' | 'loaded' | 'not-found' | 'error';
type ContactsLoadState = 'loading' | 'loaded' | 'error';

@Component({
  selector: 'app-customer-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, KeyValuePipe, ContactDetailModalComponent],
  templateUrl: './customer-detail.component.html',
  styleUrl: './customer-detail.component.scss',
})
export class CustomerDetailComponent implements OnInit {
  private readonly customerService = inject(CustomerService);
  private readonly notifications = inject(NotificationService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly loadState = signal<LoadState>('loading');
  readonly customer = signal<CustomerResponse | null>(null);
  readonly selectedContactId = signal<string | null>(null);

  readonly contactsLoadState = signal<ContactsLoadState>('loading');
  readonly contacts = signal<ContactResponse[]>([]);
  readonly contactsTotalElements = signal(0);
  readonly contactsTotalPages = signal(0);
  readonly contactsCurrentPage = signal(0);
  readonly contactsPageSize = 10;

  readonly customerName = computed(() => {
    const c = this.customer();
    if (!c) return '';
    const first = c.firstName ?? '';
    const last = c.lastName ?? '';
    const full = `${first} ${last}`.trim();
    return full || c.phone[0] || c.email[0] || c.customerId;
  });

  readonly hasCustomFields = computed(() => {
    const c = this.customer();
    return c ? Object.keys(c.customFields).length > 0 : false;
  });

  readonly contactsFirstIndex = computed(() =>
    this.contactsCurrentPage() * this.contactsPageSize + 1,
  );
  readonly contactsLastIndex = computed(() =>
    Math.min(
      (this.contactsCurrentPage() + 1) * this.contactsPageSize,
      this.contactsTotalElements(),
    ),
  );

  ngOnInit(): void {
    this.route.paramMap
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        switchMap((params) => {
          const id = params.get('id') ?? '';
          this.loadState.set('loading');
          return this.customerService.getCustomer(id).pipe(
            catchError((err: { status?: number }) => {
              if (err.status === 404) {
                this.loadState.set('not-found');
              } else {
                this.loadState.set('error');
                this.notifications.error('Nie udało się pobrać danych klienta.');
              }
              return of(null);
            }),
          );
        }),
      )
      .subscribe((customer) => {
        if (customer) {
          this.customer.set(customer);
          this.loadState.set('loaded');
          this.loadContacts();
        }
      });
  }

  loadContacts(): void {
    const id = this.customer()?.customerId;
    if (!id) return;

    this.contactsLoadState.set('loading');

    this.customerService
      .getCustomerContacts({
        customerId: id,
        page: this.contactsCurrentPage(),
        size: this.contactsPageSize,
      })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError(() => {
          this.notifications.error('Nie udało się pobrać historii kontaktów.');
          this.contactsLoadState.set('error');
          return of(null);
        }),
        finalize(() => {
          // Set 'loaded' only if not already set to 'error' by catchError.
          if (this.contactsLoadState() === 'loading') {
            this.contactsLoadState.set('loaded');
          }
        }),
      )
      .subscribe((response) => {
        if (response) {
          this.contacts.set(response.content);
          this.contactsTotalElements.set(response.totalElements);
          this.contactsTotalPages.set(response.totalPages);
          this.contactsLoadState.set('loaded');
        }
      });
  }

  onContactsPrevPage(): void {
    if (this.contactsCurrentPage() > 0) {
      this.contactsCurrentPage.update((p) => p - 1);
      this.loadContacts();
    }
  }

  onContactsNextPage(): void {
    if (this.contactsCurrentPage() + 1 < this.contactsTotalPages()) {
      this.contactsCurrentPage.update((p) => p + 1);
      this.loadContacts();
    }
  }

  goBack(): void {
    void this.router.navigate(['/supervisor/customers']);
  }

  goToEdit(): void {
    const id = this.customer()?.customerId;
    void this.router.navigate(['/supervisor/customers', id, 'edit']);
  }

  formatDuration(startedAt: string, endedAt: string | null): string {
    if (!endedAt) return '—';
    const start = new Date(startedAt).getTime();
    const end = new Date(endedAt).getTime();
    const diffSeconds = Math.floor((end - start) / 1000);
    if (diffSeconds < 0) return '—';
    const minutes = Math.floor(diffSeconds / 60);
    const seconds = diffSeconds % 60;
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
  }

  getChannelLabel(channel: string): string {
    const labels: Record<string, string> = {
      VOICE: 'Telefon',
      EMAIL: 'Email',
      CHAT: 'Chat',
      SOCIAL: 'Social',
    };
    return labels[channel] ?? channel;
  }

  getStatusLabel(status: string): string {
    const labels: Record<string, string> = {
      ACTIVE: 'Aktywny',
      ENDED: 'Zakończony',
      QUEUED: 'W kolejce',
      WRAP_UP: 'Po kontakcie',
    };
    return labels[status] ?? status;
  }

  trackByContactId(_index: number, contact: ContactResponse): string {
    return contact.contactId;
  }
}
