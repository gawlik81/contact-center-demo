import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  HostListener,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, EMPTY } from 'rxjs';
import { AgentCalendarService } from '../../../services/agent-calendar.service';
import { NotificationService } from '../../../../../core/services/notification.service';
import { RescheduleCallbackModalComponent } from '../../../components/reschedule-callback-modal/reschedule-callback-modal.component';
import { AddBreakModalComponent } from '../../../components/add-break-modal/add-break-modal.component';
import {
  AgentCalendarResponse,
  CalendarBreak,
  CalendarCallback,
  CalendarCampaign,
} from '../../../models/agent-calendar.model';

type CalendarViewMode = 'week' | 'day';

type CalendarEventEntry =
  | { kind: 'campaign'; item: CalendarCampaign }
  | { kind: 'callback'; item: CalendarCallback }
  | { kind: 'break'; item: CalendarBreak };

interface CalendarDay {
  date: Date;
  label: string;
  isToday: boolean;
  callbacks: CalendarCallback[];
  campaigns: CalendarCampaign[];
  breaks: CalendarBreak[];
  /** Unified sorted list: campaigns first (all-day), then timed events chronologically. */
  events: CalendarEventEntry[];
}

@Component({
  selector: 'app-agent-calendar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [RescheduleCallbackModalComponent, AddBreakModalComponent],
  templateUrl: './agent-calendar.component.html',
  styleUrl: './agent-calendar.component.scss',
})
export class AgentCalendarComponent implements OnInit {
  private readonly calendarService = inject(AgentCalendarService);
  private readonly notifications = inject(NotificationService);
  private readonly destroyRef = inject(DestroyRef);

  // ── View state ──────────────────────────────────────────────────────────────
  readonly viewMode = signal<CalendarViewMode>(window.innerWidth < 640 ? 'day' : 'week');
  readonly currentDate = signal<Date>(new Date());
  readonly isLoading = signal(false);
  readonly calendarData = signal<AgentCalendarResponse>({ callbacks: [], campaigns: [], breaks: [] });

  // ── Modal state ──────────────────────────────────────────────────────────────
  readonly selectedCallback = signal<CalendarCallback | null>(null);
  readonly selectedBreak = signal<CalendarBreak | null>(null);
  readonly selectedCampaign = signal<CalendarCampaign | null>(null);
  readonly addBreakMode = signal(false);

  // ── Derived state ────────────────────────────────────────────────────────────
  readonly weekDays = computed<CalendarDay[]>(() => {
    const mode = this.viewMode();
    const base = this.currentDate();
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const data = this.calendarData();

    const days: Date[] = mode === 'week' ? this.getWeekDays(base) : [this.cloneDay(base)];

    return days.map((date) => {
      const dayStart = new Date(date);
      dayStart.setHours(0, 0, 0, 0);
      const dayEnd = new Date(date);
      dayEnd.setHours(23, 59, 59, 999);

      const DONE_STATUSES = new Set(['COMPLETED', 'CANCELLED']);

      const callbacks = data.callbacks.filter((cb) => {
        if (DONE_STATUSES.has(cb.status)) return false;
        const d = new Date(cb.scheduledAt);
        return d >= dayStart && d <= dayEnd;
      });

      const breaks = data.breaks.filter((br) => {
        if (DONE_STATUSES.has(br.status)) return false;
        const d = new Date(br.startTime);
        return d >= dayStart && d <= dayEnd;
      });

      const JS_DAY_TO_NAME: Record<number, string> = {
        0: 'SUN',
        1: 'MON',
        2: 'TUE',
        3: 'WED',
        4: 'THU',
        5: 'FRI',
        6: 'SAT',
      };
      const dayName = JS_DAY_TO_NAME[date.getDay()];

      const campaigns = data.campaigns.filter((c) => {
        if (DONE_STATUSES.has(c.status)) return false;

        // Check date range
        const start = c.startDate ? new Date(c.startDate) : null;
        const end = c.endDate ? new Date(c.endDate) : null;
        if (start) start.setHours(0, 0, 0, 0);
        if (end) end.setHours(23, 59, 59, 999);
        const startOk = !start || start.getTime() <= dayEnd.getTime();
        const endOk = !end || end.getTime() >= dayStart.getTime();
        if (!startOk || !endOk) return false;

        // Check active days of week
        if (c.activeDays && c.activeDays.length > 0 && !c.activeDays.includes(dayName)) {
          return false;
        }

        return true;
      });

      // Merge callbacks and breaks into one chronologically sorted timed-events list
      const timedEvents: Array<
        | { kind: 'callback'; item: CalendarCallback; t: number }
        | { kind: 'break'; item: CalendarBreak; t: number }
      > = [
        ...callbacks.map((cb) => ({
          kind: 'callback' as const,
          item: cb,
          t: new Date(cb.scheduledAt).getTime(),
        })),
        ...breaks.map((br) => ({
          kind: 'break' as const,
          item: br,
          t: new Date(br.startTime).getTime(),
        })),
      ].sort((a, b) => a.t - b.t);

      // Campaigns are all-day: appear before timed events
      const events: CalendarEventEntry[] = [
        ...campaigns.map((c): CalendarEventEntry => ({ kind: 'campaign', item: c })),
        ...timedEvents.map(({ kind, item }): CalendarEventEntry => ({ kind, item } as CalendarEventEntry)),
      ];

      const isToday = dayStart.getTime() === today.getTime();

      return {
        date,
        label: this.formatDayLabel(date, isToday),
        isToday,
        callbacks,
        campaigns,
        breaks,
        events,
      };
    });
  });

  readonly displayLabel = computed<string>(() => {
    const mode = this.viewMode();
    const base = this.currentDate();
    if (mode === 'day') {
      return this.formatSingleDay(base);
    }
    const days = this.getWeekDays(base);
    const first = days[0];
    const last = days[6];
    if (first.getMonth() === last.getMonth()) {
      return `${first.getDate()}–${last.getDate()} ${this.monthName(first.getMonth())} ${first.getFullYear()}`;
    }
    return `${first.getDate()} ${this.monthName(first.getMonth())} – ${last.getDate()} ${this.monthName(last.getMonth())} ${last.getFullYear()}`;
  });

  // ── Icons ────────────────────────────────────────────────────────────────────
  readonly CALLBACK_ICON =
    'M20 15.5c-1.25 0-2.45-.2-3.57-.57-.35-.11-.74-.03-1.02.24l-2.2 2.2c-2.83-1.44-5.15-3.75-6.59-6.59l2.2-2.21c.28-.26.36-.65.25-1C8.7 6.45 8.5 5.25 8.5 4c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1 0 9.39 7.61 17 17 17 .55 0 1-.45 1-1v-3.5c0-.55-.45-1-1-1zM19 12h2c0-4.97-4.03-9-9-9v2c3.87 0 7 3.13 7 7zm-4 0h2c0-2.76-2.24-5-5-5v2c1.66 0 3 1.34 3 3z';
  readonly CAMPAIGN_ICON =
    'M18 11v2h4v-2h-4zm-2 6.61c.96.71 2.21 1.65 3.2 2.39.4-.53.8-1.07 1.2-1.6-.99-.74-2.24-1.68-3.2-2.4-.4.54-.8 1.08-1.2 1.61zM20.4 5.6c-.4-.53-.8-1.07-1.2-1.6-.99.74-2.24 1.68-3.2 2.4.4.53.8 1.07 1.2 1.6.96-.72 2.21-1.65 3.2-2.4zM4 9c-1.1 0-2 .9-2 2v2c0 1.1.9 2 2 2h1v4h2v-4h1l5 3V6L8 9H4zm11.5 3c0-1.33-.58-2.53-1.5-3.35v6.69c.92-.81 1.5-2.01 1.5-3.34z';
  readonly BREAK_ICON =
    'M20 3H4v10c0 2.21 1.79 4 4 4h6c2.21 0 4-1.79 4-4v-3h2c1.11 0 2-.89 2-2V5c0-1.11-.89-2-2-2zm0 5h-2V5h2v3zM4 19h16v2H4z';
  readonly CALENDAR_ICON =
    'M20 3h-1V1h-2v2H7V1H5v2H4c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 18H4V8h16v13z';

  // ── Lifecycle ────────────────────────────────────────────────────────────────
  ngOnInit(): void {
    this.loadData();
  }

  @HostListener('window:resize')
  onResize(): void {
    if (window.innerWidth < 640 && this.viewMode() === 'week') {
      this.viewMode.set('day');
    }
  }

  // ── Navigation ───────────────────────────────────────────────────────────────
  goToday(): void {
    this.currentDate.set(new Date());
    this.loadData();
  }

  navigate(direction: -1 | 1): void {
    const base = this.currentDate();
    const next = new Date(base);
    if (this.viewMode() === 'week') {
      next.setDate(next.getDate() + direction * 7);
    } else {
      next.setDate(next.getDate() + direction);
    }
    this.currentDate.set(next);
    this.loadData();
  }

  setViewMode(mode: CalendarViewMode): void {
    this.viewMode.set(mode);
    this.loadData();
  }

  // ── Data ─────────────────────────────────────────────────────────────────────
  private loadData(): void {
    const { from, to } = this.getDateRange();
    this.isLoading.set(true);

    this.calendarService
      .getCalendar(from, to)
      .pipe(
        catchError(() => {
          this.isLoading.set(false);
          this.notifications.error('Nie udalo sie zaladowac kalendarza. Sprobuj ponownie.');
          return EMPTY;
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((data) => {
        this.calendarData.set(data);
        this.isLoading.set(false);
      });
  }

  private getDateRange(): { from: Date; to: Date } {
    const base = this.currentDate();
    if (this.viewMode() === 'day') {
      const from = new Date(base);
      from.setHours(0, 0, 0, 0);
      const to = new Date(base);
      to.setHours(23, 59, 59, 999);
      return { from, to };
    }
    const days = this.getWeekDays(base);
    const from = new Date(days[0]);
    from.setHours(0, 0, 0, 0);
    const to = new Date(days[6]);
    to.setHours(23, 59, 59, 999);
    return { from, to };
  }

  // ── Event handlers ────────────────────────────────────────────────────────────
  openCallback(cb: CalendarCallback): void {
    this.selectedCallback.set(cb);
  }

  openBreak(br: CalendarBreak): void {
    this.selectedBreak.set(br);
  }

  openCampaignInline(campaign: CalendarCampaign): void {
    this.selectedCampaign.update((prev) => (prev?.id === campaign.id ? null : campaign));
  }

  onCallbackRescheduled(): void {
    this.selectedCallback.set(null);
    this.loadData();
  }

  openAddBreak(): void {
    this.selectedBreak.set(null);
    this.addBreakMode.set(true);
  }

  closeBreakModal(): void {
    this.selectedBreak.set(null);
    this.addBreakMode.set(false);
  }

  onBreakSaved(): void {
    this.closeBreakModal();
    this.loadData();
  }

  onBreakCancelled(): void {
    this.closeBreakModal();
    this.loadData();
  }

  // ── Template helpers ──────────────────────────────────────────────────────────
  parseDate(iso: string): Date {
    return new Date(iso);
  }

  formatTime(iso: string): string {
    const d = new Date(iso);
    return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`;
  }

  formatDateRange(startIso: string | null, endIso: string | null): string {
    const fmt = (iso: string | null) => {
      if (!iso) return '?';
      const d = new Date(iso);
      return `${d.getDate()} ${this.monthName(d.getMonth())} ${d.getFullYear()}`;
    };
    return `${fmt(startIso)} – ${fmt(endIso)}`;
  }

  breakTypeLabel(type: string): string {
    const map: Record<string, string> = {
      LUNCH: 'Obiad',
      SHORT_BREAK: 'Krotka przerwa',
      TRAINING: 'Szkolenie',
      MEETING: 'Spotkanie',
      OTHER: 'Inna',
    };
    return map[type] ?? type;
  }

  breakStatusLabel(status: string): string {
    const map: Record<string, string> = {
      PLANNED: 'Zaplanowana',
      ACTIVE: 'Aktywna',
      COMPLETED: 'Zakonczona',
      CANCELLED: 'Odwolana',
    };
    return map[status] ?? status;
  }

  callbackSourceLabel(source: string): string {
    const map: Record<string, string> = {
      CAMPAIGN_CALLBACK: 'Kampania',
      INBOUND_CALLBACK: 'Przychodzy',
      AGENT_MANUAL: 'Manualny',
    };
    return map[source] ?? source;
  }

  callbackStatusLabel(status: string): string {
    const map: Record<string, string> = {
      PENDING: 'Oczekujacy',
      SCHEDULED: 'Zaplanowany',
      IN_PROGRESS: 'W toku',
      COMPLETED: 'Zakończony',
      CANCELLED: 'Odwołany',
      NO_ANSWER: 'Brak odpowiedzi',
      RESCHEDULED: 'Przesunięty',
    };
    return map[status] ?? status;
  }

  campaignStatusLabel(status: string): string {
    const map: Record<string, string> = {
      ACTIVE: 'Aktywna',
      RUNNING: 'Aktywna',
      SCHEDULED: 'Zaplanowana',
      DRAFT: 'Szkic',
      PAUSED: 'Wstrzymana',
      STOPPED: 'Zatrzymana',
      COMPLETED: 'Zakończona',
      CANCELLED: 'Odwołana',
    };
    return map[status] ?? status;
  }

  formatActiveDays(days: string[]): string {
    const map: Record<string, string> = {
      MON: 'Pon',
      TUE: 'Wt',
      WED: 'Sr',
      THU: 'Czw',
      FRI: 'Pt',
      SAT: 'Sob',
      SUN: 'Ndz',
    };
    return days.map((d) => map[d] ?? d).join(', ');
  }

  readonly trackByDay = (_i: number, day: CalendarDay) => day.date.toISOString().slice(0, 10);
  readonly trackByEventId = (_i: number, e: CalendarEventEntry): string => {
    if (e.kind === 'callback') return `cb-${e.item.id}`;
    if (e.kind === 'break') return `br-${e.item.id}`;
    return `ca-${e.item.id}`;
  };

  // ── Private helpers ───────────────────────────────────────────────────────────
  private getWeekDays(base: Date): Date[] {
    const d = new Date(base);
    // Monday as first day of week
    const day = d.getDay(); // 0=Sun, 1=Mon ...
    const diff = day === 0 ? -6 : 1 - day;
    d.setDate(d.getDate() + diff);
    d.setHours(0, 0, 0, 0);
    return Array.from({ length: 7 }, (_, i) => {
      const day = new Date(d);
      day.setDate(d.getDate() + i);
      return day;
    });
  }

  private cloneDay(date: Date): Date {
    const d = new Date(date);
    d.setHours(0, 0, 0, 0);
    return d;
  }

  private formatDayLabel(date: Date, isToday: boolean): string {
    const dayNames = ['Ndz', 'Pon', 'Wt', 'Sr', 'Czw', 'Pt', 'Sob'];
    const dayName = dayNames[date.getDay()];
    const month = this.monthName(date.getMonth());
    const prefix = isToday ? 'Dzisiaj' : dayName;
    return `${prefix} ${date.getDate()} ${month}`;
  }

  private formatSingleDay(date: Date): string {
    const dayNames = ['Niedziela', 'Poniedzialek', 'Wtorek', 'Sroda', 'Czwartek', 'Piatek', 'Sobota'];
    const day = dayNames[date.getDay()];
    return `${day}, ${date.getDate()} ${this.monthName(date.getMonth())} ${date.getFullYear()}`;
  }

  private monthName(month: number): string {
    const names = ['sty', 'lut', 'mar', 'kwi', 'maj', 'cze', 'lip', 'sie', 'wrz', 'paz', 'lis', 'gru'];
    return names[month] ?? '';
  }
}
