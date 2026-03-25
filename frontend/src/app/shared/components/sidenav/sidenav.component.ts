import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { filter, map } from 'rxjs';
import { AuthService } from '../../../core/services/auth.service';
import { UserRole } from '../../../core/models/jwt-payload.model';
import { AdminMetricsService } from '../../../features/admin/services/admin-metrics.service';

export interface NavItem {
  label: string;
  route: string;
  svgPath: string;
  ariaLabel: string;
}

const ADMIN_NAV: NavItem[] = [
  {
    label: 'Dashboard',
    route: '/admin/dashboard',
    ariaLabel: 'Dashboard administratora',
    svgPath: 'M3 3h7v7H3V3zm0 11h7v7H3v-7zm11-11h7v7h-7V3zm0 11h7v7h-7v-7z',
  },
  {
    label: 'Tenants',
    route: '/admin/tenants',
    ariaLabel: 'Zarzadzanie tenantami',
    svgPath:
      'M3 21V7l9-4 9 4v14H3zm6-2h2v-4H9v4zm4 0h2v-4h-2v4zm4 0h2v-6h-2v6zm-8-6h2v-2H9v2zm4 0h2v-2h-2v2z',
  },
  {
    label: 'Uzytkownicy',
    route: '/admin/users',
    ariaLabel: 'Zarzadzanie uzytkownikami',
    svgPath:
      'M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z',
  },
  {
    label: 'Metryki',
    route: '/admin/metrics',
    ariaLabel: 'Metryki platformy',
    svgPath: 'M5 9.2h3V19H5V9.2zM10.6 5h2.8v14h-2.8V5zm5.6 8H19v6h-2.8v-6z',
  },
];

const SUPERVISOR_NAV: NavItem[] = [
  {
    label: 'Dashboard',
    route: '/supervisor/dashboard',
    ariaLabel: 'Dashboard supervisora',
    svgPath: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
  },
  {
    label: 'Agenci',
    route: '/supervisor/agents',
    ariaLabel: 'Lista agentow',
    svgPath:
      'M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z',
  },
  {
    label: 'Kolejki',
    route: '/supervisor/queues',
    ariaLabel: 'Zarzadzanie kolejkami',
    svgPath:
      'M3 13h2v-2H3v2zm0 4h2v-2H3v2zm0-8h2V7H3v2zm4 4h14v-2H7v2zm0 4h14v-2H7v2zM7 7v2h14V7H7z',
  },
  {
    label: 'Kampanie',
    route: '/supervisor/campaigns',
    ariaLabel: 'Kampanie',
    svgPath:
      'M20.45 6.23l-1.41-1.42-1.42 1.42 1.42 1.41 1.41-1.41zM11 1v2h2V1h-2zm8 11h2v-2h-2v2zM4.17 5.64L2.76 7.05l1.41 1.42 1.42-1.42-1.42-1.41zM13 17.95V22h-2v-4.05A6.002 6.002 0 016 12c0-3.31 2.69-6 6-6s6 2.69 6 6a6.002 6.002 0 01-5 5.95zM1 12h2v2H1v-2zm3-6.5L2.5 7l1 1 1.5-1.5L4 4.5z',
  },
  {
    label: 'Klienci',
    route: '/supervisor/customers',
    ariaLabel: 'Baza klientow',
    svgPath:
      'M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm-8 2.75c1.24 0 2.25 1.01 2.25 2.25S13.24 11.25 12 11.25 9.75 10.24 9.75 9 10.76 6.75 12 6.75zm4.5 9.5h-9V15c0-1.5 3-2.25 4.5-2.25s4.5.75 4.5 2.25v1.25z',
  },
  {
    label: 'Raporty',
    route: '/supervisor/reports',
    ariaLabel: 'Raporty historyczne',
    svgPath: 'M3.5 18.49l6-6.01 4 4L22 6.92l-1.41-1.41-7.09 7.97-4-4L2 16.99z',
  },
  {
    label: 'IVR',
    route: '/supervisor/ivr',
    ariaLabel: 'Edytor IVR',
    svgPath: 'M22 11V3h-7v3H9V3H2v8h7V8h2v10h4v3h7v-8h-7v3h-2V8h2v3z',
  },
  {
    label: 'Konfiguracja',
    route: '/supervisor/settings',
    ariaLabel: 'Konfiguracja',
    svgPath:
      'M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z',
  },
];

const AGENT_NAV: NavItem[] = [
  {
    label: 'Desktop',
    route: '/agent/desktop',
    ariaLabel: 'Agent Desktop',
    svgPath:
      'M12 3C6.48 3 2 7.48 2 13v1h2v-1c0-4.41 3.59-8 8-8s8 3.59 8 8v1h2v-1c0-5.52-4.48-10-10-10zm0 4c-3.31 0-6 2.69-6 6v1h2v-1c0-2.21 1.79-4 4-4s4 1.79 4 4v1h2v-1c0-3.31-2.69-6-6-6zm0 4c-1.1 0-2 .9-2 2v4h4v-4c0-1.1-.9-2-2-2z',
  },
  {
    label: 'Klienci',
    route: '/agent/customers',
    ariaLabel: 'Klienci',
    svgPath:
      'M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm-8 2.75c1.24 0 2.25 1.01 2.25 2.25S13.24 11.25 12 11.25 9.75 10.24 9.75 9 10.76 6.75 12 6.75zm4.5 9.5h-9V15c0-1.5 3-2.25 4.5-2.25s4.5.75 4.5 2.25v1.25z',
  },
];

/** Route that owns the alert badge – must be an exact string match against router.url */
const ALERT_BADGE_ROUTE = '/admin/dashboard';

@Component({
  selector: 'cc-sidenav',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, RouterLinkActive],
  templateUrl: './sidenav.component.html',
  styleUrl: './sidenav.component.scss',
})
export class SidenavComponent implements OnInit {
  protected readonly auth = inject(AuthService);
  private readonly metricsService = inject(AdminMetricsService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly router = inject(Router);

  /** Controls visibility on mobile/tablet */
  readonly isOpen = input<boolean>(false);

  /** Emits when an overlay click should close the nav */
  readonly closeRequest = output<void>();

  readonly navItems = computed<NavItem[]>(() => {
    const role: UserRole | null = this.auth.currentRole();
    switch (role) {
      case 'ADMIN':
        return ADMIN_NAV;
      case 'SUPERVISOR':
        return SUPERVISOR_NAV;
      case 'AGENT':
        return AGENT_NAV;
      default:
        return [];
    }
  });

  /** Number of active system alerts – displayed as badge on the Dashboard nav item */
  readonly alertCount = signal(0);

  /** True when the current user is an Admin (only then do we show the badge) */
  readonly isAdmin = computed(() => this.auth.currentRole() === 'ADMIN');

  /**
   * Reactive signal tracking the current URL after every completed navigation.
   *
   * Derived from NavigationEnd events so it is always updated after each
   * successful navigation, regardless of component reuse strategy. The
   * initialValue seeds the signal synchronously from router.url so the very
   * first render is already correct even before any NavigationEnd fires.
   */
  private readonly currentUrl = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => e.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  /**
   * Computed signal: true only when ALL four conditions hold simultaneously.
   *  1. Current user is ADMIN
   *  2. There is at least one active system alert
   *  3. The active URL is exactly '/admin/dashboard'
   *
   * This signal is used ONLY on the Dashboard list item (not inside the @for
   * loop for every item) so condition 3 alone guarantees it never appears on
   * Uzytkownicy, Tenants, or any other nav entry.
   */
  readonly showAlertBadge = computed(
    () => this.isAdmin() && this.alertCount() > 0 && this.currentUrl() === ALERT_BADGE_ROUTE,
  );

  ngOnInit(): void {
    // Only subscribe to the alert count stream for ADMIN users.
    // For SUPERVISOR and AGENT the badge is hidden by the isAdmin computed
    // signal in the template, but we also skip the subscription entirely so
    // the metrics service is not unnecessarily kept alive by this component.
    // Note: AdminMetricsService._poll$ is already role-gated and will never
    // issue HTTP calls for non-ADMIN users, but this guard is defense-in-depth.
    if (this.auth.currentRole() !== 'ADMIN') {
      return;
    }
    this.metricsService.alertCount$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((count) => this.alertCount.set(count));
  }

  /** Badge label capped at 99+ for display */
  alertBadgeLabel(): string {
    const count = this.alertCount();
    return count > 99 ? '99+' : String(count);
  }

  onOverlayClick(): void {
    this.closeRequest.emit();
  }

  onLinkClick(): void {
    // On mobile/tablet close after navigation
    this.closeRequest.emit();
  }

  protected readonly trackByRoute = (_index: number, item: NavItem) => item.route;
}
