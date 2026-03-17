import { ChangeDetectionStrategy, Component } from '@angular/core';

// TODO FE-014: Implement Settings page for Supervisor role.
@Component({
  selector: 'app-settings-placeholder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="placeholder-page">
      <h1>Konfiguracja</h1>
      <p>Funkcja w przygotowaniu.</p>
    </div>
  `,
  styles: `
    .placeholder-page {
      padding: 2rem;
      text-align: center;
      color: #666;
    }
  `,
})
export class SettingsPlaceholderComponent {}
