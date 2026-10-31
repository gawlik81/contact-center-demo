import { Component, input, output } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { TranslocoTestingModule } from '@jsverse/transloco';
import pl from '../../../../../public/i18n/pl.json';
import { AppShellComponent } from './app-shell.component';
import { TopNavbarComponent } from '../top-navbar/top-navbar.component';
import { SidenavComponent } from '../sidenav/sidenav.component';
import { AuthService } from '../../../core/services/auth.service';
import { BreadcrumbsComponent } from '../breadcrumbs/breadcrumbs.component';

@Component({ selector: 'cc-top-navbar', template: '' })
class StubNavbar {
  sidenavOpen = input(false);
  toggleSidenav = output<void>();
}
@Component({ selector: 'cc-sidenav', template: '' })
class StubSidenav {
  isOpen = input(false);
  closeRequest = output<void>();
  collapsedChange = output<boolean>();
}
@Component({ selector: 'cc-breadcrumbs', template: '' })
class StubBreadcrumbs {}

@Component({ template: '' })
class DummyPage {}

function setup(routes: Parameters<typeof provideRouter>[0] = []) {
  TestBed.configureTestingModule({
    imports: [
      TranslocoTestingModule.forRoot({
        langs: { pl } as never,
        translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
      }),
    ],
    providers: [provideRouter(routes)],
  });
  TestBed.overrideComponent(AppShellComponent, {
    remove: { imports: [TopNavbarComponent, SidenavComponent, BreadcrumbsComponent] },
    add: { imports: [StubNavbar, StubSidenav, StubBreadcrumbs] },
  });
  return TestBed.createComponent(AppShellComponent);
}

describe('AppShellComponent landmarks', () => {
  it('translates the skip link', () => {
    const fixture = setup();
    fixture.detectChanges();
    const link = fixture.nativeElement.querySelector('a.skip-link') as HTMLElement;
    expect(link.textContent?.trim()).toBe('Przejdź do treści');
  });

  it('renders the breadcrumb wrapper only when there are breadcrumbs', async () => {
    const fixture = setup([
      { path: 'with', component: DummyPage, data: { breadcrumb: 'nav.dashboard' } },
      { path: 'without', component: DummyPage, data: { hideBreadcrumbs: true } },
    ]);
    const router = TestBed.inject(Router);
    const root = fixture.nativeElement as HTMLElement;

    await router.navigateByUrl('/with');
    fixture.detectChanges();
    expect(root.querySelector('.shell__breadcrumbs')).not.toBeNull();

    await router.navigateByUrl('/without');
    fixture.detectChanges();
    expect(root.querySelector('.shell__breadcrumbs')).toBeNull();
  });

  it('renders exactly one <main id="main-content"> as the skip-link target', async () => {
    const fixture = setup();
    fixture.detectChanges();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelectorAll('main').length).toBe(1);
    expect(root.querySelectorAll('#main-content').length).toBe(1);
    expect(root.querySelector('main')?.id).toBe('main-content');
    expect(root.querySelector('a.skip-link')?.getAttribute('href')).toBe('#main-content');
  });

  it('skip link click prevents navigation and focuses <main>', () => {
    const fixture = setup();
    fixture.detectChanges();
    document.body.appendChild(fixture.nativeElement);
    const root = fixture.nativeElement as HTMLElement;
    const link = root.querySelector('a.skip-link') as HTMLAnchorElement;

    const event = new MouseEvent('click', { bubbles: true, cancelable: true });
    link.dispatchEvent(event);

    expect(event.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(root.querySelector('#main-content'));
    fixture.nativeElement.remove();
  });
});

describe('AppShellComponent Escape handling (FE-134)', () => {
  function pressEscape(prevented: boolean): void {
    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    if (prevented) event.preventDefault();
    document.dispatchEvent(event);
  }

  it('closes the drawer on a free Escape but ignores an Escape already used by an inner control', () => {
    const fixture = setup();
    fixture.detectChanges();
    const shell = fixture.componentInstance as unknown as { sidenavOpen: () => boolean };
    fixture.componentInstance.onToggleSidenav();
    expect(shell.sidenavOpen()).toBe(true);

    pressEscape(true);
    expect(shell.sidenavOpen()).toBe(true);

    pressEscape(false);
    expect(shell.sidenavOpen()).toBe(false);
  });
});

describe('AppShellComponent sidenav on resize (FE-123)', () => {
  function resizeTo(width: number): void {
    vi.stubGlobal('innerWidth', width);
    window.dispatchEvent(new Event('resize'));
  }

  afterEach(() => vi.unstubAllGlobals());

  function open(fixture: ReturnType<typeof setup>): () => boolean {
    const shell = fixture.componentInstance as unknown as { sidenavOpen: () => boolean };
    return () => shell.sidenavOpen();
  }

  it('closes the overlay drawer when the window narrows below xl and restores it on return', () => {
    vi.stubGlobal('innerWidth', 1366);
    const fixture = setup();
    fixture.detectChanges();
    const isOpen = open(fixture);
    expect(isOpen()).toBe(true);

    resizeTo(1024);
    expect(isOpen()).toBe(false);

    resizeTo(1366);
    expect(isOpen()).toBe(true);
  });

  it('does not touch a drawer the user opened while the window stays below xl', () => {
    vi.stubGlobal('innerWidth', 1100);
    const fixture = setup();
    fixture.detectChanges();
    const isOpen = open(fixture);
    fixture.componentInstance.onToggleSidenav();
    expect(isOpen()).toBe(true);

    resizeTo(1000); // still tablet-width: no crossing, no change
    expect(isOpen()).toBe(true);
  });
});

describe('AppShellComponent initial sidenav state (FE-123)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
    document.documentElement.classList.remove('sidenav-collapsed');
  });

  it('starts collapsed in the first frame for an agent on a laptop window, and tells <html>', () => {
    localStorage.clear();
    vi.stubGlobal('innerWidth', 1334);
    TestBed.configureTestingModule({
      providers: [{ provide: AuthService, useValue: { currentRole: () => 'AGENT' } }],
    });
    const fixture = setup();
    const shell = fixture.componentInstance as unknown as { sidenavCollapsed: () => boolean };
    expect(shell.sidenavCollapsed()).toBe(true); // before any sidenav output was emitted
    fixture.detectChanges();
    expect(document.documentElement.classList.contains('sidenav-collapsed')).toBe(true);
    fixture.destroy();
    expect(document.documentElement.classList.contains('sidenav-collapsed')).toBe(false);
  });
});
