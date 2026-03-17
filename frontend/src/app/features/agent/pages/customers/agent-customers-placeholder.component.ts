import { ChangeDetectionStrategy, Component } from '@angular/core';

// TODO FE-015: Implement Customers page for Agent role.
@Component({
  selector: 'app-agent-customers-placeholder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="placeholder-page">
      <h1>Klienci</h1>
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
export class AgentCustomersPlaceholderComponent {}
