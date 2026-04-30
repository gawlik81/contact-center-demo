import { Component, ChangeDetectionStrategy, inject } from '@angular/core';
import { Router } from '@angular/router';
import { TranslocoModule } from '@jsverse/transloco';
import { AuthService } from '../../../core/services/auth.service';

@Component({
  selector: 'app-forbidden',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [TranslocoModule],
  template: `
    <div class="forbidden-container">
      <div class="forbidden-card">
        <div class="icon" aria-hidden="true">403</div>
        <h1>{{ 'auth.forbidden.title' | transloco }}</h1>
        <p>{{ 'auth.forbidden.message' | transloco }}</p>
        <div class="actions">
          <button (click)="goBack()">{{ 'auth.forbidden.back' | transloco }}</button>
          <button class="secondary" (click)="logout()">{{ 'auth.forbidden.logout' | transloco }}</button>
        </div>
      </div>
    </div>
  `,
  styles: `
    .forbidden-container {
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 100vh;
      background: #f5f5f5;
    }
    .forbidden-card {
      background: #fff;
      padding: 3rem 2rem;
      border-radius: 8px;
      box-shadow: 0 2px 8px rgba(0,0,0,0.1);
      text-align: center;
      max-width: 420px;
    }
    .icon {
      font-size: 4rem;
      font-weight: 700;
      color: #d32f2f;
      margin-bottom: 0.5rem;
    }
    h1 { margin: 0 0 0.5rem; font-size: 1.5rem; color: #333; }
    p { color: #666; margin-bottom: 2rem; }
    .actions { display: flex; gap: 0.75rem; justify-content: center; }
    button {
      padding: 0.6rem 1.5rem;
      border: none;
      border-radius: 4px;
      font-size: 0.95rem;
      cursor: pointer;
      background: #1565c0;
      color: #fff;
    }
    button:hover { background: #0d47a1; }
    button.secondary { background: #e0e0e0; color: #333; }
    button.secondary:hover { background: #bdbdbd; }
  `,
})
export class ForbiddenComponent {
  private readonly router = inject(Router);
  private readonly authService = inject(AuthService);

  goBack(): void {
    const role = this.authService.getUserRole();
    if (role) {
      this.router.navigate([this.authService.getRoleDefaultRoute(role)]);
    } else {
      this.router.navigate(['/auth/login']);
    }
  }

  logout(): void {
    this.authService.logout();
  }
}
