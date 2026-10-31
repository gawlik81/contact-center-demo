import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslocoService, TranslocoTestingModule } from '@jsverse/transloco';
import { EMPTY, firstValueFrom, of } from 'rxjs';
import pl from '../../../../../../public/i18n/pl.json';
import { AGENT_STATUS_CONFIG, ALL_AGENT_STATUSES } from '../../models/agent-status.model';
import { AgentStatusService } from '../../services/agent-status.service';
import { AgentStatusSwitcherComponent } from './agent-status-switcher.component';

describe('AgentStatusSwitcherComponent (FE-134)', () => {
  let fixture: ComponentFixture<AgentStatusSwitcherComponent>;
  let root: HTMLElement;
  const currentStatus = signal<string>('AVAILABLE');
  const isChanging = signal(false);
  const changeStatus = vi.fn((_s: string) => of(undefined));

  const trigger = () => root.querySelector<HTMLButtonElement>('.status-btn') as HTMLButtonElement;
  const items = () => Array.from(root.querySelectorAll<HTMLButtonElement>('.status-menu__item'));
  const key = (el: Element, k: string) =>
    el.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));

  beforeEach(async () => {
    vi.clearAllMocks();
    currentStatus.set('AVAILABLE');
    isChanging.set(false);
    TestBed.configureTestingModule({
      imports: [
        AgentStatusSwitcherComponent,
        TranslocoTestingModule.forRoot({
          langs: { pl } as never,
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [
        {
          provide: AgentStatusService,
          useValue: {
            currentStatus,
            statusSince: signal<number | null>(Date.now()),
            isChanging,
            changeStatus,
          },
        },
      ],
    });
    await TestBed.compileComponents();
    await firstValueFrom(TestBed.inject(TranslocoService).load('pl'));
    fixture = TestBed.createComponent(AgentStatusSwitcherComponent);
    document.body.appendChild(fixture.nativeElement);
    root = fixture.nativeElement as HTMLElement;
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.nativeElement.remove();
  });

  it('exposes the current status in the accessible name, tone and menu-button semantics', () => {
    expect(trigger().getAttribute('aria-label')).toBe('Status: Dostępny. Zmień status');
    expect(trigger().getAttribute('aria-haspopup')).toBe('menu');
    expect(trigger().getAttribute('aria-expanded')).toBe('false');
    expect(trigger().getAttribute('data-status-tone')).toBe(AGENT_STATUS_CONFIG.AVAILABLE.tone);
  });

  it('lists every status with its tone and marks the current one as checked', () => {
    trigger().click();
    fixture.detectChanges();

    expect(root.querySelector('[role="menu"]')).not.toBeNull();
    expect(items().map((i) => i.getAttribute('data-status-tone'))).toEqual(
      ALL_AGENT_STATUSES.map((s) => AGENT_STATUS_CONFIG[s].tone),
    );
    expect(items().map((i) => i.getAttribute('aria-checked'))).toEqual(
      ALL_AGENT_STATUSES.map((s) => String(s === 'AVAILABLE')),
    );
  });

  it('changes the status through AgentStatusService and closes the menu', () => {
    trigger().click();
    fixture.detectChanges();
    items()[ALL_AGENT_STATUSES.indexOf('BREAK')].click();
    fixture.detectChanges();

    expect(changeStatus).toHaveBeenCalledExactlyOnceWith('BREAK');
    expect(root.querySelector('[role="menu"]')).toBeNull();
  });

  it('does not send a request when the current status is chosen again', () => {
    trigger().click();
    fixture.detectChanges();
    items()[0].click();
    expect(changeStatus).not.toHaveBeenCalled();
  });

  it('opens with ArrowDown on the first item and moves with arrows, Home and End', async () => {
    key(trigger(), 'ArrowDown');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(document.activeElement).toBe(items()[0]);

    key(items()[0], 'ArrowDown');
    expect(document.activeElement).toBe(items()[1]);
    key(items()[1], 'End');
    expect(document.activeElement).toBe(items()[items().length - 1]);
    key(items()[items().length - 1], 'ArrowDown');
    expect(document.activeElement).toBe(items()[0]);
    key(items()[0], 'ArrowUp');
    expect(document.activeElement).toBe(items()[items().length - 1]);
  });

  it('opens with ArrowUp on the last item', async () => {
    key(trigger(), 'ArrowUp');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(document.activeElement).toBe(items()[items().length - 1]);
  });

  it('closes with Escape and returns focus to the trigger', async () => {
    key(trigger(), 'ArrowDown');
    fixture.detectChanges();
    await fixture.whenStable();

    key(items()[0], 'Escape');
    fixture.detectChanges();

    expect(root.querySelector('[role="menu"]')).toBeNull();
    expect(document.activeElement).toBe(trigger());
  });

  it('closes on an outside click and leaves focus where the click put it (not on the trigger)', () => {
    const outside = document.createElement('button');
    document.body.appendChild(outside);
    trigger().click();
    fixture.detectChanges();

    outside.focus();
    outside.click();
    fixture.detectChanges();

    expect(root.querySelector('[role="menu"]')).toBeNull();
    expect(document.activeElement).toBe(outside);
    outside.remove();
  });

  it('listens for document clicks only while the menu is open', () => {
    const add = vi.spyOn(document, 'addEventListener');
    const remove = vi.spyOn(document, 'removeEventListener');
    const clickCalls = (spy: typeof add) => spy.mock.calls.filter((c) => c[0] === 'click').length;

    expect(clickCalls(add)).toBe(0);
    trigger().click();
    fixture.detectChanges();
    expect(clickCalls(add)).toBe(1);
    expect(clickCalls(remove)).toBe(0);

    trigger().click();
    fixture.detectChanges();
    expect(clickCalls(remove)).toBe(1);
    add.mockRestore();
    remove.mockRestore();
  });

  it('Tab from the menu moves focus to the trigger first (never to <body>) and closes the menu', async () => {
    key(trigger(), 'ArrowDown');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(document.activeElement).toBe(items()[0]);

    const event = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
    items()[0].dispatchEvent(event);
    // The default Tab action is left alone, so it continues from the trigger.
    expect(event.defaultPrevented).toBe(false);
    expect(document.activeElement).toBe(trigger());
    fixture.detectChanges();
    expect(root.querySelector('[role="menu"]')).toBeNull();
  });

  it('Escape marks the event as used (preventDefault) and does not stop propagation', async () => {
    key(trigger(), 'ArrowDown');
    fixture.detectChanges();
    await fixture.whenStable();

    let reachedDocument = false;
    const listener = () => (reachedDocument = true);
    document.addEventListener('keydown', listener);
    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    items()[0].dispatchEvent(event);
    document.removeEventListener('keydown', listener);

    expect(event.defaultPrevented).toBe(true);
    expect(reachedDocument).toBe(true);
  });

  it('Escape on the trigger closes an open menu (e.g. opened by mouse) and is marked as used', () => {
    trigger().click();
    fixture.detectChanges();
    trigger().focus();

    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    trigger().dispatchEvent(event);
    fixture.detectChanges();

    expect(event.defaultPrevented).toBe(true);
    expect(root.querySelector('[role="menu"]')).toBeNull();
    expect(document.activeElement).toBe(trigger());
  });

  it('Escape on the trigger with a closed menu is not swallowed', () => {
    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    trigger().dispatchEvent(event);
    expect(event.defaultPrevented).toBe(false);
  });

  it('links the trigger to the menu with aria-controls only while it is open', () => {
    expect(trigger().hasAttribute('aria-controls')).toBe(false);
    trigger().click();
    fixture.detectChanges();
    const menu = root.querySelector('[role="menu"]') as HTMLElement;
    expect(menu.id).not.toBe('');
    expect(trigger().getAttribute('aria-controls')).toBe(menu.id);
  });

  it('marks the trigger busy and shows a spinner instead of the chevron while changing', () => {
    expect(trigger().getAttribute('aria-busy')).toBe('false');
    expect(root.querySelector('.status-btn__chevron')).not.toBeNull();

    isChanging.set(true);
    fixture.detectChanges();

    expect(trigger().getAttribute('aria-busy')).toBe('true');
    expect(root.querySelector('.status-btn__spinner')).not.toBeNull();
    expect(root.querySelector('.status-btn__chevron')).toBeNull();
  });

  it('announces a successful change politely, and nothing on a blocked/failed one', () => {
    const live = () => root.querySelector('[role="status"]') as HTMLElement;
    expect(live().getAttribute('aria-live')).toBe('polite');
    expect(live().textContent?.trim()).toBe('');

    changeStatus.mockReturnValueOnce(EMPTY);
    trigger().click();
    fixture.detectChanges();
    items()[ALL_AGENT_STATUSES.indexOf('BUSY')].click();
    fixture.detectChanges();
    expect(live().textContent?.trim()).toBe('');

    trigger().click();
    fixture.detectChanges();
    items()[ALL_AGENT_STATUSES.indexOf('BREAK')].click();
    fixture.detectChanges();
    expect(live().textContent?.trim()).toBe('Status zmieniony na: Przerwa');
  });

  it('shows the break timer only during a break (it travels with the pill)', () => {
    expect(root.querySelector('cc-status-elapsed')).toBeNull();
    currentStatus.set('BREAK');
    fixture.detectChanges();
    const timer = root.querySelector('cc-status-elapsed [role="timer"]');
    expect(timer?.getAttribute('aria-live')).toBe('off');
  });
});
