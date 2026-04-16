import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  PLATFORM_ID,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { isPlatformBrowser } from '@angular/common';
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
  children?: NavItem[];
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
    label: 'Oddzwonienia',
    route: '/supervisor/callbacks',
    ariaLabel: 'Lista oddzwonien',
    svgPath:
      'M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z',
  },
  {
    label: 'Raporty',
    route: '/supervisor/reports',
    ariaLabel: 'Raporty historyczne',
    svgPath: 'M3.5 18.49l6-6.01 4 4L22 6.92l-1.41-1.41-7.09 7.97-4-4L2 16.99z',
    children: [
      {
        label: 'Historyczne',
        route: '/supervisor/reports',
        ariaLabel: 'Raporty historyczne agentow',
        svgPath:
          'M9 17H7v-7h2v7zm4 0h-2V7h2v10zm4 0h-2v-4h2v4zm2.5 2.1h-15V5h15v14.1zm0-16.1h-15c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h15c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z',
      },
      {
        label: 'Kontakty',
        route: '/supervisor/reports/contacts',
        ariaLabel: 'Raport kontaktow',
        svgPath:
          'M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-2 12H6v-2h12v2zm0-3H6V9h12v2zm0-3H6V6h12v2z',
      },
    ],
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
    children: [
      {
        label: 'Email',
        route: '/supervisor/settings/email',
        ariaLabel: 'Konfiguracja email',
        svgPath:
          'M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm0 4l-8 5-8-5V6l8 5 8-5v2z',
      },
      {
        label: 'Numery telefonów',
        route: '/supervisor/settings/phone-numbers',
        ariaLabel: 'Numery telefonów i routing',
        svgPath:
          'M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z',
      },
      {
        label: 'Social Media',
        route: '/supervisor/settings/integrations',
        ariaLabel: 'Integracje Social Media',
        svgPath:
          'M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z',
      },
    ],
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
  {
    label: 'Oddzwonienia',
    route: '/agent/callbacks',
    ariaLabel: 'Moje oddzwonienia',
    svgPath:
      'M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z',
  },
];

/** Route that owns the alert badge – must be an exact string match against router.url */
const ALERT_BADGE_ROUTE = '/admin/dashboard';

/** localStorage key for the collapsed state */
const COLLAPSED_STORAGE_KEY = 'cc_sidenav_collapsed';

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
  private readonly platformId = inject(PLATFORM_ID);

  /** Controls visibility on mobile/tablet */
  readonly isOpen = input<boolean>(false);

  /** Emits when an overlay click should close the nav */
  readonly closeRequest = output<void>();

  /** Emits the current collapsed state so AppShell can adjust layout */
  readonly collapsedChange = output<boolean>();

  /** Whether the sidenav is in icon-only collapsed mode (desktop only) */
  readonly isCollapsed = signal<boolean>(this._loadCollapsedState());

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
   */
  private readonly currentUrl = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => e.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  /**
   * Computed signal: true only when ALL conditions hold simultaneously:
   *  1. Current user is ADMIN
   *  2. There is at least one active system alert
   *  3. The active URL is exactly '/admin/dashboard'
   */
  readonly showAlertBadge = computed(
    () => this.isAdmin() && this.alertCount() > 0 && this.currentUrl() === ALERT_BADGE_ROUTE,
  );

  /**
   * Auto-expands any nav section whose child route matches the current URL.
   */
  private readonly _autoExpandEffect = effect(() => {
    void this.currentUrl(); // track signal dependency for auto-expand
    const items = this.navItems();
    items.forEach((item) => {
      if (
        item.children?.some((c) =>
          this.router.isActive(c.route, {
            paths: 'exact',
            queryParams: 'ignored',
            fragment: 'ignored',
            matrixParams: 'ignored',
          }),
        )
      ) {
        this.expandedSections.update((set) => {
          if (set.has(item.route)) return set;
          const next = new Set(set);
          next.add(item.route);
          return next;
        });
      }
    });
  });

  /** Persist collapsed state and notify parent whenever it changes */
  private readonly _persistCollapsedEffect = effect(() => {
    const collapsed = this.isCollapsed();
    this._saveCollapsedState(collapsed);
    this.collapsedChange.emit(collapsed);
  });

  ngOnInit(): void {
    if (this.auth.currentRole() !== 'ADMIN') {
      return;
    }
    this.metricsService.alertCount$
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((count) => this.alertCount.set(count));
  }

  /** Toggle between expanded and collapsed (icon-only) modes */
  toggleCollapsed(): void {
    this.isCollapsed.update((v) => !v);
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

  // ---- Expandable sections (nav items with children) ----

  protected readonly expandedSections = signal<Set<string>>(new Set());

  protected toggleSection(route: string): void {
    this.expandedSections.update((set) => {
      const next = new Set(set);
      if (next.has(route)) {
        next.delete(route);
      } else {
        next.add(route);
      }
      return next;
    });
  }

  protected isSectionExpanded(route: string): boolean {
    return this.expandedSections().has(route);
  }

  protected isChildActive(item: NavItem): boolean {
    return (
      item.children?.some((c) =>
        this.router.isActive(c.route, {
          paths: 'exact',
          queryParams: 'ignored',
          fragment: 'ignored',
          matrixParams: 'ignored',
        }),
      ) ?? false
    );
  }

  private _loadCollapsedState(): boolean {
    if (!isPlatformBrowser(this.platformId)) {
      return false;
    }
    try {
      return localStorage.getItem(COLLAPSED_STORAGE_KEY) === 'true';
    } catch {
      return false;
    }
  }

  private _saveCollapsedState(collapsed: boolean): void {
    if (!isPlatformBrowser(this.platformId)) {
      return;
    }
    try {
      localStorage.setItem(COLLAPSED_STORAGE_KEY, String(collapsed));
    } catch {
      // Storage unavailable – fail silently
    }
  }
}
