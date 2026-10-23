import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoTestingModule } from '@jsverse/transloco';
import pl from '../../../../../../public/i18n/pl.json';
import { AgentStatusService } from '../../services/agent-status.service';
import { StatusElapsedComponent, formatElapsed, toIsoDuration } from './status-elapsed.component';

describe('formatElapsed', () => {
  it('formats m:ss below one hour and h:mm:ss from one hour', () => {
    expect(formatElapsed(0)).toBe('0:00');
    expect(formatElapsed(65_000)).toBe('1:05');
    expect(formatElapsed(3_725_000)).toBe('1:02:05');
  });
});

describe('toIsoDuration', () => {
  it('produces ISO 8601 durations', () => {
    expect(toIsoDuration(5_000)).toBe('PT5S');
    expect(toIsoDuration(65_000)).toBe('PT1M5S');
    expect(toIsoDuration(3_725_000)).toBe('PT1H2M5S');
  });
});

describe('StatusElapsedComponent', () => {
  const statusSince = signal<number | null>(null);

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T10:00:00Z'));
    statusSince.set(null);
    TestBed.configureTestingModule({
      imports: [
        TranslocoTestingModule.forRoot({
          langs: { pl } as never,
          translocoConfig: { availableLangs: ['pl'], defaultLang: 'pl' },
        }),
      ],
      providers: [{ provide: AgentStatusService, useValue: { statusSince } }],
    });
  });

  afterEach(() => vi.useRealTimers());

  it('renders nothing while the status start is unknown', () => {
    const fixture = TestBed.createComponent(StatusElapsedComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="timer"]')).toBeNull();
  });

  it('exposes datetime and an honest session-based hint', () => {
    statusSince.set(Date.now() - 65_000);
    const fixture = TestBed.createComponent(StatusElapsedComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('time').getAttribute('datetime')).toBe('PT1M5S');
    expect(fixture.nativeElement.querySelector('[role="timer"]').getAttribute('title')).toBe(
      pl.agent.status.breakDurationHint,
    );
  });

  it('counts up every second without any live region', async () => {
    statusSince.set(Date.now());
    const fixture = TestBed.createComponent(StatusElapsedComponent);
    fixture.detectChanges();
    const read = () => fixture.nativeElement.querySelector('time').textContent.trim();
    expect(read()).toBe('0:00');

    await vi.advanceTimersByTimeAsync(3000);
    fixture.detectChanges();
    expect(read()).toBe('0:03');

    const timer = fixture.nativeElement.querySelector('[role="timer"]') as HTMLElement;
    expect(timer.getAttribute('aria-live')).toBe('off');
    expect(
      fixture.nativeElement.querySelector('[aria-live="polite"],[aria-live="assertive"]'),
    ).toBeNull();
    expect(timer.textContent).toContain(pl.agent.status.breakDuration);
  });
});
