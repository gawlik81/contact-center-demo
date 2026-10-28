import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { RouterOutlet } from '@angular/router';
import { TranslocoModule } from '@jsverse/transloco';
import { BreadcrumbService } from '../../../core/services/breadcrumb.service';
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

  /** Whether the sidenav is in collapsed (icon-only) mode */
  protected readonly sidenavCollapsed = signal(false);

  /** On desktop (>=1280px) the sidenav is always visible */
  private readonly DESKTOP_BREAKPOINT = 1280;

  private get windowWidth(): number {
    return this.document.defaultView?.innerWidth ?? 0;
  }

  ngOnInit(): void {
    // Open sidenav by default on desktop
    if (this.windowWidth >= this.DESKTOP_BREAKPOINT) {
      this.sidenavOpen.set(true);
    }
  }

  onResize(): void {
    if (this.windowWidth >= this.DESKTOP_BREAKPOINT) {
      this.sidenavOpen.set(true);
    }
  }

  onToggleSidenav(): void {
    this.sidenavOpen.update((v) => !v);
  }

  onCloseSidenav(): void {
    // Only close on mobile/tablet; on desktop keep open
    if (this.windowWidth < this.DESKTOP_BREAKPOINT) {
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
    if (this.windowWidth < this.DESKTOP_BREAKPOINT && this.sidenavOpen()) {
      this.sidenavOpen.set(false);
    }
  }

  /** Called by sidenav when collapsed state changes */
  onSidenavCollapsedChange(collapsed: boolean): void {
    this.sidenavCollapsed.set(collapsed);
  }
}
