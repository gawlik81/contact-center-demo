import { ChangeDetectionStrategy, Component } from '@angular/core';

// TODO FE-013: Implement Reports page for Supervisor role.
@Component({
  selector: 'app-reports-placeholder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="placeholder-page">
      <h1>Raporty</h1>
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
export class ReportsPlaceholderComponent {}
