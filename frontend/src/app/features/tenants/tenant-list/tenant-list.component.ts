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
import { Tenant, TenantStatus } from '../tenant.model';
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
  readonly totalElements = computed(() => this.tenants().length);

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
        page: 0,
        size: 1000,
      })
      .pipe(
        catchError(() => {
          this.notifications.error('Nie udalo sie pobrać listy tenantow. Sprobuj ponownie.');
          return of<Tenant[]>([]);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((tenants) => {
        this.tenants.set(tenants);
        this.loading.set(false);
      });
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
          this.notifications.error('Nie udalo sie dezaktywowac tenanta. Sprobuj ponownie.');
          return of(undefined);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.closeDeactivateModal();
        this.notifications.success(`Tenant "${tenant.name}" zostal dezaktywowany.`);
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
