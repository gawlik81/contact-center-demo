import { Component, ChangeDetectionStrategy } from '@angular/core';
import { RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-supervisor-shell',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterOutlet],
  template: `
    <div class="shell">
      <header class="shell-header">
        <span class="shell-title">Contact Center – Supervisor</span>
      </header>
      <main class="shell-content">
        <router-outlet />
      </main>
    </div>
  `,
  styles: `
    .shell { display: flex; flex-direction: column; min-height: 100vh; }
    .shell-header {
      background: #2e7d32;
      color: #fff;
      padding: 0.75rem 1.5rem;
      font-size: 1rem;
      font-weight: 500;
    }
    .shell-content { flex: 1; padding: 1.5rem; }
  `,
})
export class SupervisorShellComponent {}
