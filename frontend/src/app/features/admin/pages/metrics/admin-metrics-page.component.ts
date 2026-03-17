import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Placeholder for detailed platform metrics page.
 * Real-time admin metrics are available on the Dashboard (/admin/dashboard).
 */
@Component({
  selector: 'cc-admin-metrics-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="placeholder-page">
      <h1>Metryki platformy</h1>
      <p>Szczegółowe metryki platformy – w trakcie implementacji.</p>
    </div>
  `,
  styles: [
    `
      .placeholder-page {
        padding: 2rem;
        color: var(--color-text-primary, #1a1a2e);
      }
      h1 {
        margin-bottom: 0.5rem;
        font-size: 1.5rem;
      }
      p {
        color: var(--color-text-secondary, #6b7280);
      }
    `,
  ],
})
export class AdminMetricsPageComponent {}
