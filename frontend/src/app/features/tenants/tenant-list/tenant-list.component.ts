import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  signal,
  computed,
  DestroyRef,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ReactiveFormsModule, FormBuilder } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { Router } from '@angular/router';
import { debounceTime, distinctUntilChanged, catchError, of } from 'rxjs';
import { TenantService } from '../tenant.service';
import { NotificationService } from '../../../core/services/notification.service';
import { Tenant, TenantStatus, PagedResponse } from '../tenant.model';
import { TenantDeactivateModalComponent } from '../tenant-deactivate-modal/tenant-deactivate-modal.component';

@Component({
  selector: 'app-tenant-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, DatePipe, TenantDeactivateModalComponent],
  templateUrl: './tenant-list.component.html',
  styleUrl: './tenant-list.component.scss',
})
export class TenantListComponent implements OnInit {
  private readonly tenantService = inject(TenantService);
  private readonly notifications = inject(NotificationService);
  private readonly router = inject(Router);
  private readonly fb = inject(FormBuilder);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(false);
  readonly tenants = signal<Tenant[]>([]);
  readonly totalElements = signal(0);
  readonly currentPage = signal(0);
  readonly pageSize = 20;

  readonly totalPages = computed(() => Math.ceil(this.totalElements() / this.pageSize));
  readonly hasPreviousPage = computed(() => this.currentPage() > 0);
  readonly hasNextPage = computed(() => this.currentPage() < this.totalPages() - 1);

  readonly selectedTenant = signal<Tenant | null>(null);
  readonly showDeactivateModal = signal(false);

  readonly filterForm = this.fb.group({
    name: [''],
    status: ['' as TenantStatus | ''],
  });

  readonly statusOptions: { value: TenantStatus | ''; label: string }[] = [
    { value: '', label: 'Wszystkie' },
    { value: 'ACTIVE', label: 'Aktywny' },
    { value: 'INACTIVE', label: 'Nieaktywny' },
    { value: 'SUSPENDED', label: 'Zawieszony' },
  ];

  ngOnInit(): void {
    this.filterForm.valueChanges
      .pipe(
        debounceTime(300),
        distinctUntilChanged((a, b) => JSON.stringify(a) === JSON.stringify(b)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.currentPage.set(0);
        this.loadTenants();
      });

    this.loadTenants();
  }

  loadTenants(): void {
    this.loading.set(true);
    const { name, status } = this.filterForm.getRawValue();

    this.tenantService
      .getTenants({
        name: name ?? '',
        status: status ?? '',
        page: this.currentPage(),
        size: this.pageSize,
      })
      .pipe(
        catchError(() => {
          this.notifications.error('Nie udało się pobrać listy tenantów. Spróbuj ponownie.');
          return of<PagedResponse<Tenant>>({
            content: [],
            totalElements: 0,
            totalPages: 0,
            size: this.pageSize,
            number: 0,
          });
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((page) => {
        this.tenants.set(page.content);
        this.totalElements.set(page.totalElements);
        this.loading.set(false);
      });
  }

  goToPage(page: number): void {
    this.currentPage.set(page);
    this.loadTenants();
  }

  previousPage(): void {
    if (this.hasPreviousPage()) {
      this.goToPage(this.currentPage() - 1);
    }
  }

  nextPage(): void {
    if (this.hasNextPage()) {
      this.goToPage(this.currentPage() + 1);
    }
  }

  openDeactivateModal(tenant: Tenant): void {
    this.selectedTenant.set(tenant);
    this.showDeactivateModal.set(true);
  }

  closeDeactivateModal(): void {
    this.showDeactivateModal.set(false);
    this.selectedTenant.set(null);
  }

  onDeactivateConfirmed(): void {
    const tenant = this.selectedTenant();
    if (!tenant) return;

    this.tenantService
      .deactivateTenant(tenant.id)
      .pipe(
        catchError(() => {
          this.notifications.error('Nie udało się dezaktywować tenanta. Spróbuj ponownie.');
          return of(undefined);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.closeDeactivateModal();
        this.notifications.success(`Tenant "${tenant.name}" został dezaktywowany.`);
        this.loadTenants();
      });
  }

  navigateToNew(): void {
    this.router.navigate(['/admin/tenants/new']);
  }

  trackByTenantId(_index: number, tenant: Tenant): string {
    return tenant.id;
  }
}
