import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { catchError, debounceTime, distinctUntilChanged, finalize, of } from 'rxjs';
import { CustomerService } from '../services/customer.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { CustomerDeleteModalComponent } from '../customer-delete-modal/customer-delete-modal.component';
import { CustomerCreateModalComponent } from '../customer-create-modal/customer-create-modal.component';
import { CustomerEditComponent } from '../customer-edit/customer-edit.component';
import { CustomerResponse } from '../../../models/customer.model';

type SortField = 'firstName' | 'lastName' | 'createdAt';
type SortDir = 'asc' | 'desc';

@Component({
  selector: 'app-customer-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoModule,
    DatePipe,
    ReactiveFormsModule,
    CustomerDeleteModalComponent,
    CustomerCreateModalComponent,
    CustomerEditComponent,
  ],
  templateUrl: './customer-list.component.html',
  styleUrl: './customer-list.component.scss',
})
export class CustomerListComponent implements OnInit {
  private readonly customerService = inject(CustomerService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(false);
  readonly deleting = signal(false);
  readonly customers = signal<CustomerResponse[]>([]);
  readonly totalElements = signal(0);
  readonly totalPages = signal(0);
  readonly currentPage = signal(0);
  readonly pageSize = 20;

  readonly sortField = signal<SortField>('createdAt');
  readonly sortDir = signal<SortDir>('desc');

  readonly selectedCustomer = signal<CustomerResponse | null>(null);
  readonly showDeleteModal = signal(false);
  readonly showEditModal = signal(false);

  private readonly createModalRef = viewChild(CustomerCreateModalComponent);

  readonly searchForm = this.fb.group({
    query: [''],
  });

  ngOnInit(): void {
    this.searchForm.controls.query.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => {
        this.currentPage.set(0);
        this.loadCustomers();
      });

    this.loadCustomers();
  }

  loadCustomers(): void {
    this.loading.set(true);
    const query = this.searchForm.getRawValue().query ?? '';

    this.customerService
      .getCustomers({
        q: query,
        page: this.currentPage(),
        size: this.pageSize,
        sort: `${this.sortField()},${this.sortDir()}`,
      })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError(() => {
          this.notifications.error(this.transloco.translate('supervisor.customers.errorLoad'));
          return of({ content: [], totalElements: 0, totalPages: 0, page: 0, size: this.pageSize });
        }),
        finalize(() => this.loading.set(false)),
      )
      .subscribe((response) => {
        this.customers.set(response.content);
        this.totalElements.set(response.totalElements);
        this.totalPages.set(response.totalPages);
      });
  }

  onSort(field: SortField): void {
    if (this.sortField() === field) {
      this.sortDir.update((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      this.sortField.set(field);
      this.sortDir.set('asc');
    }
    this.currentPage.set(0);
    this.loadCustomers();
  }

  openDeleteModal(customer: CustomerResponse): void {
    this.selectedCustomer.set(customer);
    this.showDeleteModal.set(true);
  }

  closeDeleteModal(): void {
    this.showDeleteModal.set(false);
    this.selectedCustomer.set(null);
  }

  onDeleteConfirmed(): void {
    const customer = this.selectedCustomer();
    if (!customer) return;

    this.deleting.set(true);

    this.customerService
      .deleteCustomer(customer.customerId)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        catchError(() => {
          this.notifications.error(
            this.transloco.translate('supervisor.gdprAnonymize.errorAnonymize'),
          );
          return of(null);
        }),
        finalize(() => {
          this.deleting.set(false);
          this.closeDeleteModal();
        }),
      )
      .subscribe((result) => {
        if (result !== null) {
          this.notifications.success(
            this.transloco.translate('supervisor.gdprAnonymize.successAnonymize'),
          );
          this.loadCustomers();
        }
      });
  }

  navigateToProfile(customer: CustomerResponse): void {
    void this.router.navigate(['/supervisor/customers', customer.customerId]);
  }

  openEditModal(customer: CustomerResponse): void {
    this.selectedCustomer.set(customer);
    this.showEditModal.set(true);
  }

  closeEditModal(): void {
    this.showEditModal.set(false);
    this.selectedCustomer.set(null);
  }

  onEditSaved(): void {
    this.closeEditModal();
    this.loadCustomers();
  }

  navigateToImport(): void {
    void this.router.navigate(['/supervisor/customers/import']);
  }

  openCreateModal(): void {
    this.createModalRef()?.open();
  }

  onCustomerCreated(): void {
    this.loadCustomers();
  }

  getCustomerName(customer: CustomerResponse): string {
    const first = customer.firstName ?? '';
    const last = customer.lastName ?? '';
    const full = `${first} ${last}`.trim();
    return full || customer.phone[0] || customer.email[0] || customer.customerId;
  }

  getFirstPhone(customer: CustomerResponse): string {
    return customer.phone[0] ?? '—';
  }

  getFirstEmail(customer: CustomerResponse): string {
    return customer.email[0] ?? '—';
  }

  getExternalId(customer: CustomerResponse): string {
    return customer.externalId ?? '—';
  }

  onPrevPage(): void {
    if (this.currentPage() > 0) {
      this.currentPage.update((p) => p - 1);
      this.loadCustomers();
    }
  }

  onNextPage(): void {
    if (this.currentPage() + 1 < this.totalPages()) {
      this.currentPage.update((p) => p + 1);
      this.loadCustomers();
    }
  }

  readonly firstItemIndex = (): number => this.currentPage() * this.pageSize + 1;
  readonly lastItemIndex = (): number =>
    Math.min((this.currentPage() + 1) * this.pageSize, this.totalElements());

  trackByCustomerId(_index: number, customer: CustomerResponse): string {
    return customer.customerId;
  }

  getSortAriaLabel(field: SortField): string {
    if (this.sortField() !== field) return this.transloco.translate('common.sortAsc');
    return this.sortDir() === 'asc'
      ? this.transloco.translate('common.sortDesc')
      : this.transloco.translate('common.sortAsc');
  }

  isSortActive(field: SortField): boolean {
    return this.sortField() === field;
  }
}
