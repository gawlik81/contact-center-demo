import { TranslocoModule } from '@jsverse/transloco';
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
import { DatePipe } from '@angular/common';
import { AdminMetricsService } from '../../services/admin-metrics.service';
import { NotificationService } from '../../../../core/services/notification.service';
import { GlobalMetrics, TenantMetricsSummary } from '../../models/admin-metrics.model';

@Component({
  selector: 'app-admin-dashboard',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    TranslocoModule,DatePipe],
  templateUrl: './admin-dashboard.component.html',
  styleUrl: './admin-dashboard.component.scss',
})
export class AdminDashboardComponent implements OnInit {
  private readonly metricsService = inject(AdminMetricsService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(true);
  readonly metrics = signal<GlobalMetrics | null>(null);
  readonly hasError = signal(false);

  readonly hasAlerts = computed(() => (this.metrics()?.systemAlerts?.length ?? 0) > 0);
  readonly tenants = computed(() => this.metrics()?.tenants ?? []);
  readonly hasTenants = computed(() => this.tenants().length > 0);

  /** Pre-computed utilization map keyed by tenant id — avoids 4x calls per row in the template */
  readonly tenantUtilizationMap = computed(() => {
    const map = new Map<string, number>();
    for (const t of this.tenants()) {
      map.set(t.id, t.agentsTotal ? Math.round((t.agentsOnline / t.agentsTotal) * 100) : 0);
    }
    return map;
  });

  ngOnInit(): void {
    // Loading state from service (true only on first load)
    this.metricsService.loading$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((isLoading) => this.loading.set(isLoading));

    // Error state from service – show toast on transition to error
    this.metricsService.error$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((isError) => {
      if (isError && !this.hasError()) {
        this.notifications.error('Nie udalo sie pobrac metryk. Ponawiam za 30 sekund.');
      }
      this.hasError.set(isError);
    });

    // Metrics stream – BehaviorSubject always has last known value
    this.metricsService.globalMetrics$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((data) => this.metrics.set(data));
  }

  trackByTenantId(_index: number, tenant: TenantMetricsSummary): string {
    return tenant.id;
  }
}
