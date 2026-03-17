import { ChangeDetectionStrategy, Component } from '@angular/core';

/**
 * Placeholder for FE-008 – User / Agent management view.
 * Will be implemented as part of FE-008 task.
 */
@Component({
  selector: 'cc-admin-users',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="placeholder-page">
      <h1>Zarządzanie użytkownikami</h1>
      <p>Widok zostanie zaimplementowany w ramach zadania FE-008.</p>
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
export class AdminUsersComponent {}
