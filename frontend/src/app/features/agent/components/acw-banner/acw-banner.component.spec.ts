import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { TranslocoService, TranslocoTestingModule } from '@jsverse/transloco';
import { firstValueFrom } from 'rxjs';
import pl from '../../../../../../public/i18n/pl.json';
import { ContactTabStore } from '../../services/contact-tab.store';
import { AcwBannerComponent } from './acw-banner.component';

@Component({ template: '' })
class BlankComponent {}

describe('AcwBannerComponent', () => {
  let fixture: ComponentFixture<AcwBannerComponent>;
  let store: ContactTabStore;
  let router: Router;

  const banner = () => fixture.nativeElement.querySelector('.acw-banner') as HTMLElement | null;
  const live = () =>
    (fixture.nativeElement.querySelector('[role="status"]') as HTMLElement).textContent!.trim();

  function openPhone(status: 'ACTIVE' | 'WRAPPING', isActive = true) {
    store.tabs.set([
      {
        id: 'p',
        type: 'PHONE',
        contactId: 'c-p',
        customerName: 'Jan Kowalski',
        customerIdentifier: '+48123456789',
        status,
        isActive,
        startedAt: new Date(),
      },
      {
        id: 'm',
        type: 'EMAIL',
        contactId: 'c-m',
        customerName: 'Anna',
        customerIdentifier: 'a@b.pl',
        status: 'ACTIVE',
        isActive: !isActive,
        startedAt: new Date(),
      },
    ]);
  }

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [
        AcwBannerComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl } as never,
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        provideRouter([
          { path: 'agent/desktop', component: BlankComponent },
          { path: 'agent/customers', component: BlankComponent },
        ]),
      ],
    });
    await firstValueFrom(TestBed.inject(TranslocoService).load('pl'));
    store = TestBed.inject(ContactTabStore);
    store.reset();
    router = TestBed.inject(Router);
    await router.navigateByUrl('/agent/customers');
    fixture = TestBed.createComponent(AcwBannerComponent);
  });

  it('is absent when no call waits for its disposition', () => {
    openPhone('ACTIVE');
    fixture.detectChanges();
    expect(banner()).toBeNull();
    expect(live()).toBe('');
  });

  it('appears for a call that ended outside the desktop and announces it politely', async () => {
    openPhone('ACTIVE');
    fixture.detectChanges();
    store.markAsWrapping('p'); // what CallLifecycleService does, on any agent page
    fixture.detectChanges();

    expect(banner()).not.toBeNull();
    expect(banner()!.textContent).toContain('Uzupełnij dyspozycję');
    expect(banner()!.textContent).toContain('Jan Kowalski');
    expect(live()).toContain('Jan Kowalski');
    expect(
      fixture.nativeElement.querySelector('[role="alert"], [aria-live="assertive"]'),
    ).toBeNull();
  });

  it('never moves focus when it appears', async () => {
    const field = document.createElement('input');
    document.body.appendChild(field);
    field.focus();
    openPhone('ACTIVE');
    fixture.detectChanges();
    store.markAsWrapping('p');
    fixture.detectChanges();
    await fixture.whenStable();

    expect(banner()).not.toBeNull();
    expect(document.activeElement).toBe(field);
    field.remove();
  });

  it('the button activates the ACW tab, requests focus for the form and goes to the desktop', async () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    openPhone('WRAPPING', false);
    fixture.detectChanges();

    (banner()!.querySelector('button') as HTMLButtonElement).click();

    expect(store.activeTab()?.id).toBe('p');
    expect(store.consumeAcwFocus('p')).toBe(true);
    expect(navigate).toHaveBeenCalledWith(['/agent/desktop']);
  });

  it('is hidden on the desktop while the ACW tab is in front, visible while another tab is active', async () => {
    openPhone('WRAPPING', true);
    await router.navigateByUrl('/agent/desktop');
    fixture.detectChanges();
    expect(banner()).toBeNull();

    store.setActiveTab('m');
    fixture.detectChanges();
    expect(banner()).not.toBeNull();
  });

  it('disappears once the disposition is saved (tab closed)', () => {
    openPhone('WRAPPING', false);
    fixture.detectChanges();
    expect(banner()).not.toBeNull();
    store.closeTab('p');
    fixture.detectChanges();
    expect(banner()).toBeNull();
  });

  it('stays visible while the calendar view covers the active ACW tab, and the button closes it', async () => {
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    openPhone('WRAPPING', true);
    await router.navigateByUrl('/agent/desktop');
    store.calendarOpen.set(true);
    fixture.detectChanges();
    expect(banner()).not.toBeNull();

    (banner()!.querySelector('button') as HTMLButtonElement).click();
    expect(store.calendarOpen()).toBe(false);
    expect(navigate).toHaveBeenCalled();
  });

  it('a call ending into ACW while the calendar is open: banner appears, focus untouched', async () => {
    const field = document.createElement('input');
    document.body.appendChild(field);
    field.focus();
    await router.navigateByUrl('/agent/desktop');
    openPhone('ACTIVE', true);
    store.calendarOpen.set(true);
    fixture.detectChanges();
    expect(banner()).toBeNull();

    store.markAsWrapping('p');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(banner()).not.toBeNull();
    expect(store.calendarOpen()).toBe(true); // we rely on the banner, the calendar stays
    expect(document.activeElement).toBe(field);
    field.remove();
  });

  it('announces once per ACW tab: route and tab switches do not repeat it', async () => {
    openPhone('WRAPPING', false);
    fixture.detectChanges();
    const first = live();
    expect(first).toContain('Jan Kowalski');

    const changes: string[] = [];
    const region = fixture.nativeElement.querySelector('[role="status"]') as HTMLElement;
    new MutationObserver(() => changes.push(region.textContent ?? '')).observe(region, {
      childList: true,
      characterData: true,
      subtree: true,
    });

    await router.navigateByUrl('/agent/desktop'); // other tab active: still visible
    fixture.detectChanges();
    store.setActiveTab('p'); // in front: banner hides
    fixture.detectChanges();
    store.setActiveTab('m'); // behind again: banner returns
    fixture.detectChanges();
    await Promise.resolve();

    expect(banner()).not.toBeNull();
    expect(live()).toBe(first);
    expect(changes).toEqual([]);
  });

  it('is a labelled landmark and sits before the app content when hosted by the shell', () => {
    openPhone('WRAPPING', false);
    fixture.detectChanges();
    const section = banner()!;
    expect(section.tagName).toBe('SECTION');
    expect(section.getAttribute('aria-label')).toBe(pl.agent.acwBanner.title);
  });
});
