import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  effect,
  inject,
  signal,
} from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { RouterOutlet } from '@angular/router';
import { TranslocoModule } from '@jsverse/transloco';
import { AuthService } from '../../../core/services/auth.service';
import { BreadcrumbService } from '../../../core/services/breadcrumb.service';
import { BREAKPOINT_XL } from '../../styles/breakpoints';
import { initialSidenavCollapsed } from '../../utils/sidenav-collapsed';
import { TopNavbarComponent } from '../top-navbar/top-navbar.component';
import { SidenavComponent } from '../sidenav/sidenav.component';
import { BreadcrumbsComponent } from '../breadcrumbs/breadcrumbs.component';

@Component({
  selector: 'cc-app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    RouterOutlet,
    TranslocoModule,
    TopNavbarComponent,
    SidenavComponent,
    BreadcrumbsComponent,
  ],
  templateUrl: './app-shell.component.html',
  styleUrl: './app-shell.component.scss',
  host: {
    '(window:resize)': 'onResize()',
    '(document:keydown.escape)': 'onEscape($event)',
  },
})
export class AppShellComponent implements OnInit {
  private readonly document = inject(DOCUMENT);
  protected readonly breadcrumbService = inject(BreadcrumbService);
  protected readonly sidenavOpen = signal(false);

  /**
   * Whether the sidenav is in collapsed (icon-only) mode. Computed synchronously from the same
   * source as the sidenav itself, so the first frame is already right (no wide-then-rail jump).
   */
  protected readonly sidenavCollapsed = signal(
    initialSidenavCollapsed(
      inject(AuthService).currentRole(),
      inject(DOCUMENT).defaultView ?? undefined,
    ),
  );

  /** `sidenav-collapsed` on <html> lets fixed overlays (ACW card) stay clear of the sidenav. */
  private readonly rootClassEffect = effect(() => {
    this.document.documentElement.classList.toggle('sidenav-collapsed', this.sidenavCollapsed());
  });

  private readonly releaseRootClass = inject(DestroyRef).onDestroy(() =>
    this.document.documentElement.classList.remove('sidenav-collapsed'),
  );

  /** Last known side of the breakpoint, so a resize acts only when the window crosses it. */
  private wasDesktop = false;

  private get windowWidth(): number {
    return this.document.defaultView?.innerWidth ?? 0;
  }

  private get isDesktop(): boolean {
    return this.windowWidth >= BREAKPOINT_XL;
  }

  ngOnInit(): void {
    // Open sidenav by default on desktop
    this.wasDesktop = this.isDesktop;
    if (this.wasDesktop) {
      this.sidenavOpen.set(true);
    }
  }

  onResize(): void {
    const desktop = this.isDesktop;
    if (desktop === this.wasDesktop) return; // same side: keep what the user chose (drawer etc.)
    this.wasDesktop = desktop;
    // Crossing into desktop restores the column; crossing out closes it, otherwise the overlay
    // drawer (with its backdrop) would open by itself on top of the page (FE-123).
    this.sidenavOpen.set(desktop);
  }

  onToggleSidenav(): void {
    this.sidenavOpen.update((v) => !v);
  }

  onCloseSidenav(): void {
    // Only close on mobile/tablet; on desktop keep open
    if (!this.isDesktop) {
      this.sidenavOpen.set(false);
    }
  }

  /**
   * Skip link: with `<base href="/">` a plain `#main-content` anchor resolves to the app root
   * and triggers a full page reload (losing the in-memory JWT). Keep the href for link
   * semantics, but move focus to <main> ourselves and stay on the current route.
   */
  onSkipToContent(event: Event): void {
    event.preventDefault();
    this.document.getElementById('main-content')?.focus();
  }

  /** Keyboard: close drawer on Escape */
  onEscape(event?: Event): void {
    // An inner control (e.g. the agent status menu) already used this Esc: do not also close
    // the drawer.
    if (event?.defaultPrevented) return;
    if (!this.isDesktop && this.sidenavOpen()) {
      this.sidenavOpen.set(false);
    }
  }

  /** Called by sidenav when collapsed state changes */
  onSidenavCollapsedChange(collapsed: boolean): void {
    this.sidenavCollapsed.set(collapsed);
  }
}
