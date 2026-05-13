import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  EventEmitter,
  OnInit,
  Output,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  EMPTY,
  Subject,
  catchError,
  debounceTime,
  distinctUntilChanged,
  filter,
  switchMap,
  tap,
} from 'rxjs';
import { CustomerSummary } from '../../models/customer-search.model';
import { CustomerSearchService } from '../../services/customer-search.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { AgentCustomerCardComponent } from './agent-customer-card.component';
import { ManualCallbackModalComponent } from './manual-callback-modal/manual-callback-modal.component';
import { AdHocEmailModalComponent } from './adhoc-email-modal/adhoc-email-modal.component';
import { OutboundCallService } from '../../services/outbound-call.service';
import { EmailService } from '../../services/email.service';

const MIN_QUERY_LENGTH = 2;

@Component({
  selector: 'app-agent-customers-tab',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoModule,
    DatePipe,
    FormsModule,
    AgentCustomerCardComponent,
    ManualCallbackModalComponent,
    AdHocEmailModalComponent,
  ],
  templateUrl: './agent-customers-tab.component.html',
  styleUrl: './agent-customers-tab.component.scss',
})
export class AgentCustomersTabComponent implements OnInit {
  /** Emitted when the agent clicks "Zamow oddzwonienie" on a customer card. */
  @Output() scheduleCallback = new EventEmitter<CustomerSummary>();

  private readonly searchService = inject(CustomerSearchService);
  private readonly notifications = inject(NotificationService);
  private readonly outboundCallService = inject(OutboundCallService);
  private readonly emailService = inject(EmailService);
  private readonly transloco = inject(TranslocoService);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly searchQuery = signal('');
  protected readonly customers = signal<CustomerSummary[]>([]);
  protected readonly isLoading = signal(false);
  protected readonly hasSearched = signal(false);
  protected readonly selectedCustomer = signal<CustomerSummary | null>(null);
  protected readonly callbackCustomer = signal<CustomerSummary | null>(null);
  protected readonly emailCustomer = signal<CustomerSummary | null>(null);

  /** Intermediate subject for debouncing raw input events */
  private readonly queryInput$ = new Subject<string>();

  ngOnInit(): void {
    this.queryInput$
      .pipe(
        debounceTime(300),
        distinctUntilChanged(),
        tap((q) => {
          this.searchQuery.set(q);
          if (q.length < MIN_QUERY_LENGTH) {
            this.customers.set([]);
            this.hasSearched.set(false);
            this.isLoading.set(false);
          }
        }),
        filter((q) => q.length >= MIN_QUERY_LENGTH),
        tap(() => {
          this.isLoading.set(true);
          this.hasSearched.set(false);
        }),
        switchMap((q) =>
          this.searchService.search(q).pipe(
            catchError(() => {
              this.isLoading.set(false);
              this.notifications.error('Nie udalo sie wyszukac klientow. Sprobuj ponownie.');
              return EMPTY;
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((response) => {
        this.customers.set(response.content ?? []);
        this.isLoading.set(false);
        this.hasSearched.set(true);
      });
  }

  protected onInputChange(value: string): void {
    this.queryInput$.next(value);
  }

  protected onViewDetails(customer: CustomerSummary): void {
    this.selectedCustomer.set(customer);
  }

  protected onScheduleCallback(customer: CustomerSummary): void {
    this.scheduleCallback.emit(customer);
    this.callbackCustomer.set(customer);
  }

  protected onCallbackScheduled(): void {
    this.callbackCustomer.set(null);
  }

  protected onCallbackCancelled(): void {
    this.callbackCustomer.set(null);
  }

  protected clearSearch(): void {
    this.queryInput$.next('');
    this.searchQuery.set('');
    this.customers.set([]);
    this.hasSearched.set(false);
    this.selectedCustomer.set(null);
  }

  protected onInitiateCall(event: { customer: CustomerSummary; phoneNumber: string }): void {
    this.outboundCallService
      .call(event.phoneNumber, event.customer.customerId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () =>
          this.notifications.success(this.transloco.translate('agent.customers.callInitiated')),
        error: () =>
          this.notifications.error(this.transloco.translate('agent.customers.callError')),
      });
  }

  protected onInitiateCallFromDrawer(phoneNumber: string): void {
    const customer = this.selectedCustomer();
    if (!customer) return;
    this.onInitiateCall({ customer, phoneNumber });
  }

  protected onSendEmail(customer: CustomerSummary): void {
    this.emailCustomer.set(customer);
  }

  protected onEmailSent(): void {
    this.emailCustomer.set(null);
  }

  protected onEmailCancelled(): void {
    this.emailCustomer.set(null);
  }

  protected onSendEmailFromDrawer(): void {
    const customer = this.selectedCustomer();
    if (customer) this.emailCustomer.set(customer);
  }

  protected readonly trackByCustomerId = (_i: number, c: CustomerSummary) => c.customerId;
}
