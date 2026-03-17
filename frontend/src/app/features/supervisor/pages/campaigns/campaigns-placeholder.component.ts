import { ChangeDetectionStrategy, Component } from '@angular/core';

// TODO FE-011: Implement Campaigns management page for Supervisor role.
@Component({
  selector: 'app-campaigns-placeholder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="placeholder-page">
      <h1>Kampanie</h1>
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
export class CampaignsPlaceholderComponent {}
