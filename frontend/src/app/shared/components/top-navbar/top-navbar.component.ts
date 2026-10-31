import { ChangeDetectionStrategy, Component, computed, inject, input, output } from '@angular/core';
import { DecimalPipe, NgClass } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { AuthService } from '../../../core/services/auth.service';
import { UserRole } from '../../../core/models/jwt-payload.model';
import { LanguageSwitcherComponent } from '../language-switcher/language-switcher.component';
import { ThemeService, ThemeMode } from '../../../core/services/theme.service';
import { AgentKpiService } from '../../services/agent-kpi.service';
import { AgentKpiResponse } from '../../models/agent-kpi.model';
import {
  formatHoursMinutes,
  formatMinSec,
  splitHoursMinutes,
  splitMinSec,
} from '../../utils/duration-format.util';
import { BRAND } from '../../../core/brand';
import { AgentStatusSwitcherComponent } from '../../../features/agent/components/agent-status-switcher/agent-status-switcher.component';

@Component({
  selector: 'cc-top-navbar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    NgClass,
    DecimalPipe,
    LanguageSwitcherComponent,
    TranslocoModule,
    AgentStatusSwitcherComponent,
  ],
  templateUrl: './top-navbar.component.html',
  styleUrl: './top-navbar.component.scss',
})
export class TopNavbarComponent {
  protected readonly auth = inject(AuthService);
  private readonly transloco = inject(TranslocoService);
  protected readonly theme = inject(ThemeService);
  private readonly agentKpiService = inject(AgentKpiService);

  /** Latest KPI snapshot — null until first successful response or on HTTP error. */
  protected readonly kpi = toSignal<AgentKpiResponse | null>(this.agentKpiService.kpi$, {
    initialValue: null,
  });

  protected readonly brand = BRAND;

  private readonly router = inject(Router);

  /** Current URL, kept in a signal so OnPush re-evaluates after every navigation. */
  private readonly url = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      map((e) => e.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );

  /**
   * FE-134: the agent status switcher is shown to the AGENT role on every `/agent/*` page (and
   * only there), so the top bar stays unchanged for supervisor and admin.
   */
  protected readonly showAgentStatus = computed(
    () => this.auth.currentRole() === 'AGENT' && /^\/agent(\/|\?|#|$)/.test(this.url()),
  );

  /** Whether the sidenav is currently open (used for aria-expanded) */
  readonly sidenavOpen = input<boolean>(false);

  /** Emits when the hamburger button is clicked */
  readonly toggleSidenav = output<void>();

  onToggleSidenav(): void {
    this.toggleSidenav.emit();
  }

  protected readonly formatMinSec = formatMinSec;
  protected readonly formatHoursMinutes = formatHoursMinutes;

  /** Active language, used only to pick the plural category (translation is done by the pipe). */
  private readonly lang = toSignal(this.transloco.langChanges$, {
    initialValue: this.transloco.getActiveLang(),
  });

  /** Plural rules per language (built once per language change, not on every recompute). */
  private readonly pluralRules = computed(() => new Intl.PluralRules(this.lang()));

  // i18n note: all four plural keys exist in every language on purpose. top-navbar.kpi-layout.spec
  // types en/de/uk by the shape of pl.json, so key sets must match; en/de never select few/many.
  /**
   * Work time for assistive tech as translation parts, e.g. "3 godziny" + "45 minut".
   * Only keys and counts live here; the `transloco` pipe in the template resolves the text and
   * re-renders once the language file has loaded. Zero parts are dropped ("45 minut", "1 godzina"),
   * except a lone "0 minut" so the value is never empty.
   */
  protected readonly workTimeParts = computed(() => {
    const seconds = this.kpi()?.workTimeSeconds;
    if (seconds == null) return [];
    const { hours, minutes } = splitHoursMinutes(seconds);
    const rules = this.pluralRules();
    const parts: { key: string; count: number }[] = [];
    if (hours > 0) {
      parts.push({ key: `agent.desktop.kpiWorkTimeHours_${rules.select(hours)}`, count: hours });
    }
    if (minutes > 0 || hours === 0) {
      parts.push({
        key: `agent.desktop.kpiWorkTimeMinutes_${rules.select(minutes)}`,
        count: minutes,
      });
    }
    return parts;
  });

  /** AHT split for the screen-reader text ("3 min 5 s"); units are invariant abbreviations. */
  protected readonly ahtParts = computed(() => {
    const k = this.kpi();
    return k ? splitMinSec(k.avgHandleTimeSeconds) : null;
  });

  onLogout(): void {
    this.auth.logout();
  }

  setTheme(mode: ThemeMode): void {
    this.theme.setMode(mode);
  }

  getRoleBadgeClass(role: UserRole | null): string {
    switch (role) {
      case 'SUPER_ADMIN':
        return 'badge badge--super-admin';
      case 'ADMIN':
        return 'badge badge--admin';
      case 'SUPERVISOR':
        return 'badge badge--supervisor';
      case 'AGENT':
        return 'badge badge--agent';
      default:
        return 'badge';
    }
  }

  getRoleLabel(role: UserRole | null): string {
    switch (role) {
      case 'SUPER_ADMIN':
        return this.transloco.translate('role.superAdmin');
      case 'ADMIN':
        return this.transloco.translate('role.admin');
      case 'SUPERVISOR':
        return this.transloco.translate('role.supervisor');
      case 'AGENT':
        return this.transloco.translate('role.agent');
      default:
        return '';
    }
  }
}
