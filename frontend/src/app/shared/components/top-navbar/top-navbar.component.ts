import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { NgClass } from '@angular/common';
import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
import { AuthService } from '../../../core/services/auth.service';
import { UserRole } from '../../../core/models/jwt-payload.model';
import { LanguageSwitcherComponent } from '../language-switcher/language-switcher.component';
import { ThemeService, ThemeMode } from '../../../core/services/theme.service';

@Component({
  selector: 'cc-top-navbar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [NgClass, LanguageSwitcherComponent, TranslocoModule],
  templateUrl: './top-navbar.component.html',
  styleUrl: './top-navbar.component.scss',
})
export class TopNavbarComponent {
  protected readonly auth = inject(AuthService);
  private readonly transloco = inject(TranslocoService);
  protected readonly theme = inject(ThemeService);

  /** Whether the sidenav is currently open (used for aria-expanded) */
  readonly sidenavOpen = input<boolean>(false);

  /** Emits when the hamburger button is clicked */
  readonly toggleSidenav = output<void>();

  onToggleSidenav(): void {
    this.toggleSidenav.emit();
  }

  onLogout(): void {
    this.auth.logout();
  }

  setTheme(mode: ThemeMode): void {
    this.theme.setMode(mode);
  }

  getRoleBadgeClass(role: UserRole | null): string {
    switch (role) {
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
