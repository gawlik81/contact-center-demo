import {
  ChangeDetectionStrategy,
  Component,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { DOCUMENT } from '@angular/common';
import { RouterOutlet } from '@angular/router';
import { TopNavbarComponent } from '../top-navbar/top-navbar.component';
import { SidenavComponent } from '../sidenav/sidenav.component';
import { BreadcrumbsComponent } from '../breadcrumbs/breadcrumbs.component';

@Component({
  selector: 'cc-app-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet, TopNavbarComponent, SidenavComponent, BreadcrumbsComponent],
  templateUrl: './app-shell.component.html',
  styleUrl: './app-shell.component.scss',
  host: {
    '(window:resize)': 'onResize()',
    '(document:keydown.escape)': 'onEscape()',
  },
})
export class AppShellComponent implements OnInit {
  private readonly document = inject(DOCUMENT);
  protected readonly sidenavOpen = signal(false);

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

  /** Keyboard: close drawer on Escape */
  onEscape(): void {
    if (this.windowWidth < this.DESKTOP_BREAKPOINT && this.sidenavOpen()) {
      this.sidenavOpen.set(false);
    }
  }
}
