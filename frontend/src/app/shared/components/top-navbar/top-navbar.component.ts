import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { DecimalPipe, NgClass } from '@angular/common';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { AuthService } from '../../../core/services/auth.service';
import { UserRole } from '../../../core/models/jwt-payload.model';
import { LanguageSwitcherComponent } from '../language-switcher/language-switcher.component';
import { ThemeService, ThemeMode } from '../../../core/services/theme.service';
import { AgentKpiService } from '../../services/agent-kpi.service';
import { AgentKpiResponse } from '../../models/agent-kpi.model';

@Component({
  selector: 'cc-top-navbar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NgClass, DecimalPipe, LanguageSwitcherComponent, TranslocoModule],
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

  /** Expose Math so the template can call Math.min() without workarounds. */
  protected readonly Math = Math;

  /** Whether the sidenav is currently open (used for aria-expanded) */
  readonly sidenavOpen = input<boolean>(false);

  /** Emits when the hamburger button is clicked */
  readonly toggleSidenav = output<void>();

  onToggleSidenav(): void {
    this.toggleSidenav.emit();
  }

  protected formatSeconds(totalSeconds: number): string {
    const m = Math.floor(totalSeconds / 60);
    const s = Math.floor(totalSeconds % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  }

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
