import { ChangeDetectionStrategy, Component } from '@angular/core';

// TODO FE-010: Implement Queues management page for Supervisor role.
@Component({
  selector: 'app-queues-placeholder',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="placeholder-page">
      <h1>Kolejki</h1>
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
export class QueuesPlaceholderComponent {}
